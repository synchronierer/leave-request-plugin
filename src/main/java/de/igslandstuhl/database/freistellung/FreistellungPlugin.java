package de.igslandstuhl.database.freistellung;

import de.igslandstuhl.database.plugins.Plugin;
import de.igslandstuhl.database.server.Server;
import de.igslandstuhl.database.server.webserver.WebPath;
import de.igslandstuhl.database.server.webserver.ContentType;
import de.igslandstuhl.database.server.webserver.Status;
import de.igslandstuhl.database.server.webserver.access.AccessManager;
import de.igslandstuhl.database.server.webserver.access.AccessLevel;
import de.igslandstuhl.database.server.webserver.handlers.HttpHandler;
import de.igslandstuhl.database.server.webserver.requests.APIPostRequest;
import de.igslandstuhl.database.server.webserver.requests.GetRequest;
import de.igslandstuhl.database.server.webserver.requests.RequestType;
import de.igslandstuhl.database.server.webserver.responses.PostResponse;
import de.igslandstuhl.database.server.webserver.responses.HttpResponse;
import de.igslandstuhl.database.api.User;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/** Eigenständiges Freistellungsmodul; alle Berechtigungen werden serverseitig geprüft. */
public final class FreistellungPlugin extends Plugin {
    static final String PERSONAL = "nach Rücksprache mit der Schulleitung";
    private FreistellungConfig config;
    private volatile boolean mailWorkerStarted;
    private static final Set<String> TYPES = Set.of("FORTBILDUNG", "DIENSTLICHE_GRUENDE", "KLASSENFAHRT", "PERSOENLICHE_GRUENDE", "UNTERRICHTSGANG");
    private static final Set<String> DECISIONS = Set.of("GENEHMIGT", "GENEHMIGT_NACHHALTEN", "NICHT_MOEGLICH", "RUECKSPRACHE");
    private static final String APPLY = "freistellung_apply";
    private static final String ORGA = "freistellung_orga";
    private static final String SL = "freistellung_sl";
    private static final String MAIL_ADMIN = "freistellung_mail_admin";
    private static final Pattern MAIL = Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}$");

    @Override protected void onLoad() {
        config = new FreistellungConfig(this);
        getLogger().info("Freistellung plugin loading");
        try { schema(); } catch (SQLException e) { throw new IllegalStateException("Freistellung-Schema konnte nicht angelegt werden", e); }
        for (String path : List.of("/freistellung-submit", "/freistellung-orga", "/freistellung-sl", "/freistellung-delete", "/freistellung-resend", "/freistellung-withdraw"))
            HttpHandler.registerPostRequestHandler(path, AccessLevel.TEACHER, this::post);
        HttpHandler.registerPostRequestHandler("/freistellung-data", AccessLevel.TEACHER, this::post);
        HttpHandler.registerPostRequestHandler("/freistellung-role-info", AccessLevel.TEACHER, this::post);
        HttpHandler.registerPostRequestHandler("/freistellung-mail-admin-data", AccessLevel.ADMIN, this::post);
        HttpHandler.registerPostRequestHandler("/freistellung-mail-profile", AccessLevel.ADMIN, this::post);
        HttpHandler.registerPostRequestHandler("/freistellung-mail-test", AccessLevel.ADMIN, this::post);
        HttpHandler.registerGetRequestHandler("/freistellung-attachment", AccessLevel.TEACHER, this::download);
        startMailWorker();
    }
    @Override protected void onEnable() {
        getLogger().info("Freistellung plugin enabled");
        startMailWorker();
    }
    @Override protected void onDisable() { }
    @Override public FreistellungConfig getConfig() { return config; }

    private Connection db() { return Server.getInstance().getConnection().getSQLConnection(); }
    private void schema() throws SQLException {
        try (Statement s = db().createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_requests (id TEXT PRIMARY KEY, applicant TEXT NOT NULL, type TEXT NOT NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL, start_hour INTEGER NOT NULL, end_hour INTEGER NOT NULL, reason TEXT NOT NULL, group_name TEXT, status TEXT NOT NULL, orga_decision TEXT, orga_comment TEXT, orga_user TEXT, orga_at TEXT, sl_decision TEXT, sl_internal_comment TEXT, sl_message TEXT, sl_user TEXT, sl_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT, deleted_by TEXT, school_year TEXT NOT NULL, attachment_id TEXT, attachment_name TEXT, attachment_size INTEGER, attachment_sha256 TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_history (id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL REFERENCES freistellung_requests(id), actor TEXT NOT NULL, function_name TEXT, from_status TEXT, to_status TEXT NOT NULL, event_at TEXT NOT NULL, detail TEXT)");
            try { s.executeUpdate("ALTER TABLE freistellung_history ADD COLUMN function_name TEXT"); } catch (SQLException ignored) { }
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_roles (username TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('ORGALEITER','SCHULLEITUNG')), PRIMARY KEY(username, role))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_user_profiles (username TEXT PRIMARY KEY, notification_email TEXT, updated_at TEXT NOT NULL, updated_by TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL, recipient TEXT NOT NULL, event TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, sent_at TEXT, last_error TEXT, audience TEXT, subject TEXT, body TEXT, recipient_username TEXT, recipient_email TEXT)");
            for (String column : List.of("audience TEXT", "subject TEXT", "body TEXT", "recipient_username TEXT", "recipient_email TEXT")) try { s.executeUpdate("ALTER TABLE freistellung_notifications ADD COLUMN " + column); } catch (SQLException ignored) { }
            s.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS freistellung_notification_once ON freistellung_notifications(request_id,recipient,event)");
        }
    }

    // Historical freistellung_roles rows remain available for review. They do not
    // recreate permission overrides: only an explicit administrator action may do so.

    private HttpResponse post(APIPostRequest rq) {
        User user = rq.getUser();
        if (user == null || (!user.isTeacher() && !user.isAdmin())) return PostResponse.unauthorized(rq);
        try {
            return switch (rq.getPath().split("\\?")[0]) {
                case "/freistellung-submit" -> submit(rq, user);
                case "/freistellung-orga" -> decide(rq, user, false);
                case "/freistellung-sl" -> decide(rq, user, true);
                case "/freistellung-delete" -> delete(rq, user);
                case "/freistellung-resend" -> resend(rq, user);
                case "/freistellung-withdraw" -> withdraw(rq, user);
                case "/freistellung-role-info" -> roleInfo(rq, user);
                case "/freistellung-mail-admin-data" -> mailAdminData(rq, user);
                case "/freistellung-mail-profile" -> saveMailProfile(rq, user);
                case "/freistellung-mail-test" -> sendMailTest(rq, user);
                default -> data(rq, user);
            };
        } catch (IllegalArgumentException e) { return PostResponse.json(Status.BAD_REQUEST, Map.of("error", e.getMessage()), rq); }
        catch (SQLException | IOException e) { getLogger().error("Freistellung request failed", e); return PostResponse.internalServerError("Interner Fehler", rq); }
    }

    private HttpResponse submit(APIPostRequest rq, User user) throws SQLException, IOException {
        if (!hasPermission(user, APPLY)) return PostResponse.forbidden("Kein Freistellungs-Antragsrecht", rq);
        String type = required(rq,"type").toUpperCase(Locale.ROOT), start = required(rq,"startDate"), end = required(rq,"endDate");
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unbekannte Antragsart");
        int sh = hour(Integer.parseInt(required(rq,"startHour"))), eh = hour(Integer.parseInt(required(rq,"endHour")));
        LocalDate sd = LocalDate.parse(start), ed = LocalDate.parse(end);
        if (ed.isBefore(sd) || (sd.equals(ed) && eh < sh)) throw new IllegalArgumentException("Ungültiger Zeitraum");
        String reason = type.equals("PERSOENLICHE_GRUENDE") ? PERSONAL : required(rq,"reason");
        // New applications use one common free-text field. The legacy column is
        // retained only so historical group information remains readable.
        String group = null;
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        Attachment attachment = attachment(rq);
        try (PreparedStatement p = db().prepareStatement("INSERT INTO freistellung_requests (id,applicant,type,start_date,end_date,start_hour,end_hour,reason,group_name,status,created_at,updated_at,school_year) VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,?)")) {
            p.setString(1,id); p.setString(2,user.getUsername()); p.setString(3,type); p.setString(4,start); p.setString(5,end); p.setInt(6,sh); p.setInt(7,eh); p.setString(8,reason); p.setString(9,group); p.setString(10,"EINGEREICHT"); p.setString(11,now); p.setString(12,now); p.setString(13,schoolYear(sd)); p.executeUpdate();
        }
        if (attachment != null) { Path dir = storage(); Files.createDirectories(dir); Path file = dir.resolve(attachment.id()); Files.write(file, attachment.bytes(), StandardOpenOption.CREATE_NEW); try (PreparedStatement p=db().prepareStatement("UPDATE freistellung_requests SET attachment_id=?,attachment_name=?,attachment_size=?,attachment_sha256=? WHERE id=?")) { p.setString(1,attachment.id()); p.setString(2,attachment.name()); p.setInt(3,attachment.bytes().length); p.setString(4,attachment.sha256()); p.setString(5,id); p.executeUpdate(); } }
        history(id,user.getUsername(),"ANTRAGSTELLER",null,"EINGEREICHT",now,"Antrag eingereicht");
        notifyNewApplication(id);
        return PostResponse.json(Map.of("id",id,"status","EINGEREICHT","attachment",attachment != null),rq);
    }

    private HttpResponse data(APIPostRequest rq, User user) throws SQLException {
        boolean orga = hasPermission(user, ORGA), sl = hasPermission(user, SL);
        if (!hasPermission(user, APPLY) && !orga && !sl) return PostResponse.forbidden("Kein Freistellungs-Antragsrecht", rq);
        boolean reviewer = orga || sl;
        Map<String,String> filter = fields(rq);
        StringBuilder sql = new StringBuilder("SELECT * FROM freistellung_requests WHERE deleted_at IS NULL");
        List<String> values = new ArrayList<>();
        if (!reviewer) { sql.append(" AND applicant=?"); values.add(user.getUsername()); }
        else {
            addFilter(sql, values, filter, "status", DECISIONS, Set.of("EINGEREICHT","ORGA_RUECKSPRACHE","ORGA_ABGESCHLOSSEN","ABGESCHLOSSEN","ZURUECKGEZOGEN"));
            addFilter(sql, values, filter, "schoolYear", null, null);
            addFilter(sql, values, filter, "applicant", null, null);
            addFilter(sql, values, filter, "type", TYPES, TYPES);
        }
        sql.append(" ORDER BY created_at DESC");
        JsonArray out = new JsonArray();
        try (PreparedStatement p = db().prepareStatement(sql.toString())) { for (int i=0;i<values.size();i++) p.setString(i+1, values.get(i)); try (ResultSet r=p.executeQuery()) { while(r.next()) out.add(publicJson(r, orga, sl)); } }
        return PostResponse.json(out,rq);
    }

    private static void addFilter(StringBuilder sql, List<String> values, Map<String,String> filter, String key, Set<String> allowed, Set<String> validation) {
        String value=filter.get(key); if(value==null||value.isBlank()) return; value=value.trim(); if(validation!=null&&!validation.contains(value.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("Ungültiger Filter: "+key); sql.append(" AND ").append(key.equals("schoolYear")?"school_year":key).append("=?"); values.add(key.equals("status")||key.equals("type")?value.toUpperCase(Locale.ROOT):value);
    }

    private HttpResponse roleInfo(APIPostRequest rq, User user) throws SQLException {
        return PostResponse.json(Map.of("apply", hasPermission(user, APPLY), "orga", hasPermission(user, ORGA), "sl", hasPermission(user, SL)), rq);
    }

    private HttpResponse download(GetRequest rq) throws SQLException, IOException {
        User user=Server.getInstance().getWebServer().getSessionManager().getSessionUser(rq); String id=rq.getArgument("id"); if(id==null||id.isBlank())return HttpResponse.error(rq,Status.BAD_REQUEST);
        try(PreparedStatement p=db().prepareStatement("SELECT applicant,attachment_id,attachment_name,attachment_size FROM freistellung_requests WHERE id=? AND deleted_at IS NULL")){p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())return HttpResponse.error(rq,Status.NOT_FOUND);String applicant=r.getString(1);if(!user.getUsername().equals(applicant)&&!(hasPermission(user,ORGA)||hasPermission(user,SL)))return HttpResponse.error(rq,Status.FORBIDDEN);String aid=r.getString(2);if(aid==null)return HttpResponse.error(rq,Status.NOT_FOUND);Path f=storage().resolve(aid).normalize();if(!f.getParent().equals(storage().toAbsolutePath().normalize())||!Files.isRegularFile(f))return HttpResponse.error(rq,Status.NOT_FOUND);byte[] b=Files.readAllBytes(f);if(b.length>2*1024*1024)return HttpResponse.error(rq,Status.INTERNAL_SERVER_ERROR);return new HttpResponse(){public Status getStatus(){return Status.OK;}public de.igslandstuhl.database.server.webserver.ContentType getContentType(){return ContentType.PDF;}public de.igslandstuhl.database.server.webserver.requests.HttpRequest getHttpRequest(){return rq;}public void respond(java.io.PrintStream out){out.print("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/pdf\r\nContent-Disposition: attachment; filename=\"freistellung.pdf\"\r\nContent-Length: "+b.length+"\r\n\r\n");out.write(b,0,b.length);out.flush();}};}}
    }

    private HttpResponse decide(APIPostRequest rq, User user, boolean sl) throws SQLException {
        String id=required(rq,"id"), decision=required(rq,"decision").toUpperCase(Locale.ROOT);
        if (!DECISIONS.contains(decision)) throw new IllegalArgumentException("Ungültige Bewertung");
        String function = sl ? SL : ORGA;
        if (!hasPermission(user,function)) return PostResponse.forbidden("Keine Berechtigung",rq);
        db().setAutoCommit(false);
        try (PreparedStatement q=db().prepareStatement("SELECT status,applicant FROM freistellung_requests WHERE id=? AND deleted_at IS NULL")) {
            q.setString(1,id);
            try(ResultSet r=q.executeQuery()) {
                if(!r.next()) return PostResponse.notFound("Vorgang nicht gefunden",rq);
                String old=r.getString(1), applicant=r.getString(2);
                if(sl && !old.equals("ORGA_ABGESCHLOSSEN")) throw new IllegalArgumentException("Orga-Bearbeitung noch nicht abgeschlossen");
                if(!sl && !old.equals("EINGEREICHT") && !old.equals("ORGA_RUECKSPRACHE")) throw new IllegalArgumentException("Vorgang nicht bearbeitbar");
                String status=sl?(decision.equals("RUECKSPRACHE")?"ORGA_ABGESCHLOSSEN":"ABGESCHLOSSEN"):(decision.equals("RUECKSPRACHE")?"ORGA_RUECKSPRACHE":"ORGA_ABGESCHLOSSEN"), now=Instant.now().toString();
                String sql=sl?"UPDATE freistellung_requests SET sl_decision=?,sl_internal_comment=?,sl_message=?,sl_user=?,sl_at=?,status=?,updated_at=? WHERE id=? AND status=?":"UPDATE freistellung_requests SET orga_decision=?,orga_comment=?,orga_user=?,orga_at=?,status=?,updated_at=? WHERE id=? AND status=?";
                try(PreparedStatement u=db().prepareStatement(sql)) {
                    int i=1; u.setString(i++,decision);
                    if(sl){u.setString(i++,optional(rq,"internalComment"));u.setString(i++,optional(rq,"message"));u.setString(i++,user.getUsername());u.setString(i++,now);}
                    else {u.setString(i++,optional(rq,"comment"));u.setString(i++,user.getUsername());u.setString(i++,now);}
                    u.setString(i++,status);u.setString(i++,now);u.setString(i++,id);u.setString(i,old);
                    if(u.executeUpdate()!=1) throw new IllegalArgumentException("Vorgang wurde inzwischen bearbeitet");
                }
                history(id,user.getUsername(),sl?"SCHULLEITUNG":"ORGALEITUNG",old,status,now,decision);
                db().commit();
                if(sl && !decision.equals("RUECKSPRACHE")) notifyFinal(id, applicant);
                else if(!sl && !decision.equals("RUECKSPRACHE")) notifyOrgaForward(id);
                return PostResponse.json(Map.of("id",id,"status",status),rq);
            }
        } catch(Throwable t){try{db().rollback();}catch(SQLException ignored){} if(t instanceof SQLException e)throw e; if(t instanceof IllegalArgumentException e)throw e; throw new SQLException(t);}
        finally{db().setAutoCommit(true);}
    }

    private HttpResponse delete(APIPostRequest rq, User user) throws SQLException { if(!(hasPermission(user,ORGA)||hasPermission(user,SL))) return PostResponse.forbidden("Keine Berechtigung",rq); if(!"yes".equalsIgnoreCase(required(rq,"confirmation"))) throw new IllegalArgumentException("Löschbestätigung fehlt"); String id=required(rq,"id"),now=Instant.now().toString(); try(PreparedStatement p=db().prepareStatement("UPDATE freistellung_requests SET deleted_at=?,deleted_by=?,updated_at=? WHERE id=? AND deleted_at IS NULL")){p.setString(1,now);p.setString(2,user.getUsername());p.setString(3,now);p.setString(4,id);if(p.executeUpdate()!=1)return PostResponse.notFound("Vorgang nicht gefunden",rq);}history(id,user.getUsername(),functionFor(user),null,"GELOESCHT",now,"Revisionsfähige Löschung");return PostResponse.json(Map.of("deleted",true),rq); }
    private HttpResponse resend(APIPostRequest rq, User user) throws SQLException { if(!(hasPermission(user,ORGA)||hasPermission(user,SL)))return PostResponse.forbidden("Keine Berechtigung",rq);try(PreparedStatement p=db().prepareStatement("UPDATE freistellung_notifications SET state='PENDING', attempts=attempts+1 WHERE id=?")){p.setLong(1,Integer.parseInt(required(rq,"notificationId")));p.executeUpdate();}return PostResponse.json(Map.of("queued",true),rq); }
    private HttpResponse withdraw(APIPostRequest rq, User user) { return PostResponse.forbidden("Ein eingereichter Antrag kann nicht mehr geändert oder zurückgezogen werden",rq); }

    private JsonObject publicJson(ResultSet r, boolean orga, boolean sl) throws SQLException {
        boolean reviewer = orga || sl;
        JsonObject o = new JsonObject();
        o.addProperty("id", r.getString("id"));
        o.addProperty("applicant", r.getString("applicant"));
        o.addProperty("type", r.getString("type"));
        o.addProperty("startDate", r.getString("start_date"));
        o.addProperty("endDate", r.getString("end_date"));
        o.addProperty("startHour", r.getInt("start_hour"));
        o.addProperty("endHour", r.getInt("end_hour"));
        o.addProperty("reason", r.getString("reason"));
        o.addProperty("groupName", r.getString("group_name"));
        o.addProperty("status", r.getString("status"));
        o.addProperty("schoolYear", r.getString("school_year"));
        o.addProperty("createdAt", r.getString("created_at"));
        o.addProperty("completedAt", r.getString("sl_at"));
        o.addProperty("attachment", r.getString("attachment_id") != null);
        o.addProperty("attachmentName", r.getString("attachment_name"));
        if (orga) {
            o.addProperty("slInternalComment", r.getString("sl_internal_comment"));
            o.addProperty("slMessage", r.getString("sl_message"));
            o.addProperty("orgaDecision", r.getString("orga_decision"));
            o.addProperty("orgaComment", r.getString("orga_comment"));
        } else if (sl) {
            o.addProperty("orgaDecision", r.getString("orga_decision"));
            o.addProperty("orgaComment", r.getString("orga_comment"));
            o.addProperty("slDecision", r.getString("sl_decision"));
            o.addProperty("slInternalComment", r.getString("sl_internal_comment"));
            o.addProperty("slMessage", r.getString("sl_message"));
        } else {
            // Applicant-facing data is an explicit allow-list. Internal
            // comments, actor IDs, functions and workflow enums never enter
            // this response, even though they remain stored for auditing.
            o.addProperty("slDecision", r.getString("sl_decision"));
            o.addProperty("slMessage", r.getString("sl_message"));
        }
        o.add("history", history(r.getString("id"), reviewer));
        return reviewer ? o : applicantView(o);
    }

    /**
     * Final server-side allow-list for the applicant API response. Keeping
     * this boundary separate from the reviewer response prevents an internal
     * field from becoming visible merely because a new property was added to
     * the shared request object.
     */
    static JsonObject applicantView(JsonObject internal) {
        JsonObject safe = new JsonObject();
        for (String field : List.of("id", "applicant", "type", "startDate", "endDate", "startHour",
                "endHour", "reason", "groupName", "status", "schoolYear", "createdAt", "completedAt",
                "attachment", "attachmentName", "slDecision", "slMessage")) {
            if (!internal.has(field)) continue;
            JsonElement value = internal.get(field);
            if ("status".equals(field) && !value.isJsonNull()) safe.addProperty(field, applicantStatus(value.getAsString()));
            else if ("slDecision".equals(field) && !value.isJsonNull()) safe.addProperty(field, publicDecision(value.getAsString()));
            else safe.add(field, value);
        }
        JsonArray publicHistory = new JsonArray();
        JsonArray history = internal.getAsJsonArray("history");
        if (history != null) {
            for (JsonElement item : history) {
                if (!item.isJsonObject()) continue;
                JsonObject source = item.getAsJsonObject(), target = new JsonObject();
                if (source.has("at")) target.add("at", source.get("at"));
                if (source.has("status") && !source.get("status").isJsonNull())
                    target.addProperty("status", applicantStatus(source.get("status").getAsString()));
                if (source.has("message")) target.add("message", source.get("message"));
                publicHistory.add(target);
            }
        }
        safe.add("history", publicHistory);
        return safe;
    }

    private JsonArray history(String requestId, boolean reviewer) throws SQLException {
        JsonArray history = new JsonArray();
        try (PreparedStatement p = db().prepareStatement(
                "SELECT actor,function_name,from_status,to_status,event_at,detail FROM freistellung_history WHERE request_id=? ORDER BY id")) {
            p.setString(1, requestId);
            try (ResultSet h = p.executeQuery()) {
                while (h.next()) {
                    JsonObject e = new JsonObject();
                    if (reviewer) {
                        e.addProperty("actor", h.getString(1));
                        e.addProperty("function", h.getString(2));
                        e.addProperty("fromStatus", h.getString(3));
                        e.addProperty("toStatus", h.getString(4));
                        e.addProperty("at", h.getString(5));
                        e.addProperty("detail", h.getString(6));
                    } else {
                        // Only public time and a public status label are
                        // exposed to applicants. Do not serialize any
                        // internal function, actor, comment or raw status.
                        e.addProperty("at", h.getString(5));
                        e.addProperty("status", applicantStatus(h.getString(4)));
                        e.addProperty("message", applicantHistoryMessage(h.getString(4)));
                    }
                    history.add(e);
                }
            }
        }
        return history;
    }

    private static String applicantHistoryMessage(String status) {
        return switch (status) {
            case "EINGEREICHT" -> "Antrag eingereicht.";
            case "ORGA_RUECKSPRACHE" -> "Rücksprache erforderlich.";
            case "ORGA_ABGESCHLOSSEN" -> "Die Vorprüfung ist abgeschlossen.";
            case "ABGESCHLOSSEN" -> "Endgültige Entscheidung gespeichert.";
            case "ZURUECKGEZOGEN" -> "Antrag zurückgezogen.";
            default -> "Bearbeitungsstand aktualisiert.";
        };
    }

    private static String applicantStatus(String status) {
        return switch (status) {
            case "EINGEREICHT" -> "Eingereicht";
            case "ORGA_RUECKSPRACHE" -> "Rücksprache erforderlich";
            case "ORGA_ABGESCHLOSSEN" -> "Vorprüfung abgeschlossen";
            case "ABGESCHLOSSEN" -> "Entschieden";
            case "ZURUECKGEZOGEN" -> "Zurückgezogen";
            default -> "In Bearbeitung";
        };
    }

    private static String publicDecision(String decision) {
        if (decision == null) return null;
        return switch (decision) {
            case "GENEHMIGT" -> "Genehmigt";
            case "GENEHMIGT_NACHHALTEN" -> "Genehmigt – Stunden nachhalten";
            case "NICHT_MOEGLICH" -> "Nicht möglich";
            case "RUECKSPRACHE" -> "Rücksprache erforderlich";
            default -> "Entschieden";
        };
    }
    private void requirePermission(User u,String permission){if(!hasPermission(u,permission))throw new SecurityException("Keine Berechtigung");}
    private boolean hasPermission(User u,String permission){
        try {
            Class<?> p=permissionClass("de.igslandstuhl.database.permissions.Permission");
            Object definition=p.getMethod("getByName",String.class).invoke(null,permission);
            if(definition==null)return false;
            Class<?> effects=permissionClass("de.igslandstuhl.database.permissions.UserEffect");
            Object effect=effects.getMethod("get",User.class).invoke(null,u);
            if(effect==null)return false;
            Object values=effects.getMethod("getPermissions").invoke(effect);
            for(Object value:(Object[])values)if(permission.equals(p.getMethod("getName").invoke(value)))return true;
        } catch (ReflectiveOperationException | LinkageError ignored) { }
        return false;
    }

    /** Resolve Permission Manager classes from the sibling plugin class loader. */
    private static Class<?> permissionClass(String name) throws ReflectiveOperationException {
        List<ClassLoader> loaders=new ArrayList<>();
        loaders.add(FreistellungPlugin.class.getClassLoader());
        if(Thread.currentThread().getContextClassLoader()!=null)loaders.add(Thread.currentThread().getContextClassLoader());
        ClassLoader own=FreistellungPlugin.class.getClassLoader();
        try {
            Class<?> loaderType=Class.forName("de.igslandstuhl.database.plugins.PluginLoader",false,own);
            Object loader=loaderType.getMethod("getInstance").invoke(null);
            Object infos=loaderType.getMethod("getPluginInfos").invoke(loader);
            for(Object info:(Iterable<?>)infos){
                Method description=info.getClass().getDeclaredMethod("description"); description.setAccessible(true);
                Object pluginDescription=description.invoke(info);
                Method id=pluginDescription.getClass().getMethod("id");
                if(!"permission-manager".equals(id.invoke(pluginDescription)))continue;
                Method classLoader=info.getClass().getDeclaredMethod("classLoader"); classLoader.setAccessible(true);
                loaders.add((ClassLoader)classLoader.invoke(info));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        ReflectiveOperationException failure=new ClassNotFoundException(name);
        for(ClassLoader loader:loaders)if(loader!=null)try{return Class.forName(name,false,loader);}catch(ClassNotFoundException e){failure=e;}
        throw failure;
    }
    private String functionFor(User u){if(hasPermission(u,ORGA))return "ORGALEITUNG";if(hasPermission(u,SL))return "SCHULLEITUNG";return "ANTRAGSTELLER";}
    private void history(String id,String actor,String function,String from,String to,String at,String detail)throws SQLException{try(PreparedStatement p=db().prepareStatement("INSERT INTO freistellung_history(request_id,actor,function_name,from_status,to_status,event_at,detail) VALUES(?,?,?,?,?,?,?)")){p.setString(1,id);p.setString(2,actor);p.setString(3,function);p.setString(4,from);p.setString(5,to);p.setString(6,at);p.setString(7,detail);p.executeUpdate();}}
    private void notifyNewApplication(String id) {
        try { Map<String,String> c=requestContext(id); queueAudience(id, ORGA, "NEUER_ANTRAG", "Neuer Freistellungsantrag", applicationBody(c, "Ein neuer Freistellungsantrag wartet auf die Orga-Leitung.")); }
        catch (Exception e) { getLogger().warn("Freistellungsantrag gespeichert, Orga-Benachrichtigung konnte nicht vorbereitet werden", e); }
    }
    private void notifyOrgaForward(String id) {
        try { Map<String,String> c=requestContext(id); String body=applicationBody(c,"Der Antrag wurde von der Orga-Leitung bewertet und wartet auf die Schulleitung.")+"\nOrga-Bewertung: "+value(c,"orga_decision")+"\nOrga-Mitteilung: "+value(c,"orga_comment"); queueAudience(id, SL, "ORGA_WEITERLEITUNG", "Freistellungsantrag zur Schulleitungsbearbeitung", body); }
        catch (Exception e) { getLogger().warn("Orga-Benachrichtigung konnte nicht vorbereitet werden", e); }
    }
    private void notifyFinal(String id,String applicant) {
        try { Map<String,String> c=requestContext(id); String decision=value(c,"sl_decision"); String body="Der Freistellungsantrag ist abschließend entschieden.\n"+applicationBody(c,"Finale Entscheidung: "+decision+"\nAbschlussmitteilung: "+value(c,"sl_message")); queueUser(id, applicant, "ABSCHLUSS_ANTRAGSTELLER", "Freistellungsantrag abschließend entschieden", body); queueAudience(id, ORGA, "ABSCHLUSS_ORGA", "Freistellungsantrag abschließend entschieden", applicationBody(c,"Finale Entscheidung: "+decision+"\nAbschlussmitteilung: "+value(c,"sl_message")+"\n\nUntis-Eintrag vornehmen.")); }
        catch (Exception e) { getLogger().warn("Abschlussbenachrichtigung konnte nicht vorbereitet werden", e); }
    }
    private Map<String,String> requestContext(String id) throws SQLException {
        Map<String,String> c=new HashMap<>();
        try(PreparedStatement p=db().prepareStatement("SELECT applicant,type,start_date,end_date,start_hour,end_hour,reason,group_name,orga_decision,orga_comment,sl_decision,sl_message FROM freistellung_requests WHERE id=? AND deleted_at IS NULL")){p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Vorgang nicht gefunden"); for(String k:List.of("applicant","type","start_date","end_date","reason","group_name","orga_decision","orga_comment","sl_decision","sl_message"))c.put(k,r.getString(k));c.put("start_hour",Integer.toString(r.getInt("start_hour")));c.put("end_hour",Integer.toString(r.getInt("end_hour")));}}
        return c;
    }
    private static String value(Map<String,String> c,String key){return c.get(key)==null?"—":c.get(key);}
    private static String applicationBody(Map<String,String> c,String prefix){return prefix+"\n\nAntragsteller: "+value(c,"applicant")+"\nArt: "+value(c,"type")+"\nZeitraum: "+value(c,"start_date")+" bis "+value(c,"end_date")+"\nStunden: "+value(c,"start_hour")+" bis "+value(c,"end_hour")+"\nLerngruppe: "+value(c,"group_name")+"\nBegründung: "+value(c,"reason");}
    private void queueAudience(String id,String permission,String event,String subject,String body) throws SQLException {
        int count=0;
        for(User candidate:User.getAllUsers()) if(hasPermission(candidate,permission)){String email=notificationEmail(candidate.getUsername());if(email==null){recordMailWarning(id,permission,event,candidate.getUsername(),"Keine gültige Mailadresse hinterlegt");continue;}queueRecipient(id,candidate.getUsername(),email,permission,event,subject,body);count++;}
        if(count==0) recordMailWarning(id,permission,event,null,"Keine aktive berechtigte Person mit gültiger Mailadresse");
    }
    private void queueUser(String id,String username,String event,String subject,String body) throws SQLException {String email=notificationEmail(username);if(email==null){recordMailWarning(id,"ANTRAGSTELLER",event,username,"Keine gültige Mailadresse hinterlegt");return;}queueRecipient(id,username,email,"ANTRAGSTELLER",event,subject,body);}
    private void queueRecipient(String id,String username,String email,String audience,String event,String subject,String body)throws SQLException{try(PreparedStatement p=db().prepareStatement("INSERT OR IGNORE INTO freistellung_notifications(request_id,recipient,event,state,created_at,audience,subject,body,recipient_username,recipient_email) VALUES(?,?,?,?,?,?,?,?,?,?)")){p.setString(1,id);p.setString(2,email);p.setString(3,event);p.setString(4,"PENDING");p.setString(5,Instant.now().toString());p.setString(6,audience);p.setString(7,subject);p.setString(8,body);p.setString(9,username);p.setString(10,email);p.executeUpdate();}}
    private void recordMailWarning(String id,String audience,String event,String username,String message)throws SQLException{try(PreparedStatement p=db().prepareStatement("INSERT OR IGNORE INTO freistellung_notifications(request_id,recipient,event,state,created_at,audience,last_error,recipient_username) VALUES(?,?,?,?,?,?,?,?)")){p.setString(1,id);p.setString(2,username==null?"(kein Empfänger)":username);p.setString(3,event);p.setString(4,"BLOCKED_NO_RECIPIENT");p.setString(5,Instant.now().toString());p.setString(6,audience);p.setString(7,message);p.setString(8,username);p.executeUpdate();}}
    private String notificationEmail(String username) throws SQLException {try(PreparedStatement p=db().prepareStatement("SELECT notification_email FROM freistellung_user_profiles WHERE username=?")){p.setString(1,username);try(ResultSet r=p.executeQuery()){if(!r.next())return null;String e=r.getString(1);return validEmail(e)?e.trim():null;}}}
    private static boolean validEmail(String email){return email!=null&&!email.contains("\r")&&!email.contains("\n")&&MAIL.matcher(email.trim()).matches();}

    private boolean mailAdminAuthorized(APIPostRequest rq, User user) {
        return AccessManager.getInstance().isAdminRouteAuthorized(user, rq.getPath(), rq);
    }
    private HttpResponse mailAdminData(APIPostRequest rq,User user)throws SQLException{if(!mailAdminAuthorized(rq,user))return PostResponse.forbidden("Nur Admins im Administratormodus dürfen die Mailverwaltung öffnen",rq);JsonArray profiles=new JsonArray();for(User candidate:User.getAllUsers()){JsonObject o=new JsonObject();o.addProperty("username",candidate.getUsername());o.addProperty("admin",candidate.isAdmin());String email=notificationEmail(candidate.getUsername());o.addProperty("notificationEmail",email==null?"":email);o.addProperty("emailValid",email!=null);o.addProperty("orga",hasPermission(candidate,ORGA));o.addProperty("sl",hasPermission(candidate,SL));o.addProperty("apply",hasPermission(candidate,APPLY));profiles.add(o);}JsonArray notifications=new JsonArray();try(PreparedStatement p=db().prepareStatement("SELECT id,event,audience,recipient_username,recipient_email,state,created_at,sent_at,last_error,subject FROM freistellung_notifications ORDER BY id DESC LIMIT 50");ResultSet r=p.executeQuery()){while(r.next()){JsonObject o=new JsonObject();o.addProperty("id",r.getLong(1));o.addProperty("event",r.getString(2));o.addProperty("audience",r.getString(3));o.addProperty("recipient",r.getString(4));o.addProperty("recipientEmail",r.getString(5));o.addProperty("state",r.getString(6));o.addProperty("createdAt",r.getString(7));o.addProperty("sentAt",r.getString(8));o.addProperty("error",r.getString(9));o.addProperty("subject",r.getString(10));notifications.add(o);}}return PostResponse.json(Map.of("transport",SmtpTransport.status(),"profiles",profiles,"notifications",notifications),rq);}
    private HttpResponse saveMailProfile(APIPostRequest rq,User user)throws SQLException{if(!mailAdminAuthorized(rq,user))return PostResponse.forbidden("Nur Admins im Administratormodus dürfen Mailadressen verwalten",rq);String username=required(rq,"username"),raw=optional(rq,"notificationEmail");if(raw!=null)raw=raw.trim();if(raw!=null&&!raw.isBlank()&&!validEmail(raw))throw new IllegalArgumentException("Ungültige Mailadresse");if(raw!=null&&raw.isBlank())raw=null;boolean exists=false;for(User candidate:User.getAllUsers())if(username.equals(candidate.getUsername())){exists=true;break;}if(!exists)throw new IllegalArgumentException("Benutzer nicht gefunden");try(PreparedStatement p=db().prepareStatement("INSERT INTO freistellung_user_profiles(username,notification_email,updated_at,updated_by) VALUES(?,?,?,?) ON CONFLICT(username) DO UPDATE SET notification_email=excluded.notification_email,updated_at=excluded.updated_at,updated_by=excluded.updated_by")){p.setString(1,username);p.setString(2,raw);p.setString(3,Instant.now().toString());p.setString(4,user.getUsername());p.executeUpdate();}return PostResponse.json(Map.of("username",username,"notificationEmail",raw==null?"":raw),rq);}
    private HttpResponse sendMailTest(APIPostRequest rq,User user)throws SQLException{if(!mailAdminAuthorized(rq,user))return PostResponse.forbidden("Nur Admins im Administratormodus dürfen Testmails auslösen",rq);String recipient=required(rq,"recipient");if(!validEmail(recipient))throw new IllegalArgumentException("Ungültige Empfängeradresse");String id="ADMIN_TEST:"+UUID.randomUUID();queueRecipient(id,user.getUsername(),recipient,"ADMIN_TEST","ADMIN_TEST","Arcanum-Mailtest","Dies ist eine synthetische Testmail aus der Freistellungs-Mailverwaltung.");return PostResponse.json(Map.of("queued",true,"id",id),rq);}
    private Path storage(){String configured=System.getenv("ARCANUM_FREISTELLUNG_DIR");return Path.of(configured==null||configured.isBlank()?"/srv/arcanum/dev/shared/freistellung-attachments":configured).toAbsolutePath().normalize();}
    private record Attachment(String id,String name,byte[] bytes,String sha256){}
    private Attachment attachment(APIPostRequest rq){if(rq.getHeader().getContentType()==null||!rq.getHeader().getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/"))return null;String ct=rq.getHeader().getContentType();int bi=ct.toLowerCase(Locale.ROOT).indexOf("boundary=");if(bi<0)throw new IllegalArgumentException("Multipart-Grenze fehlt");String boundary=ct.substring(bi+9).replace("\"","").trim();String raw=new String(rq.getRawBody(),StandardCharsets.ISO_8859_1);for(String part:raw.split(java.util.regex.Pattern.quote("--"+boundary))){int headerEnd=part.indexOf("\r\n\r\n");if(headerEnd<0||!part.contains("name=\"attachment\""))continue;int fileName=part.indexOf("filename=\"");if(fileName<0)continue;int fileEnd=part.indexOf('"',fileName+10);String name=part.substring(fileName+10,fileEnd);String payload=part.substring(headerEnd+4);if(payload.endsWith("\r\n"))payload=payload.substring(0,payload.length()-2);byte[] bytes=payload.getBytes(StandardCharsets.ISO_8859_1);if(name.isBlank()||bytes.length==0)return null;if(bytes.length>2*1024*1024)throw new IllegalArgumentException("PDF ist größer als 2 MiB");if(bytes.length<8||!new String(bytes,0,5,StandardCharsets.ISO_8859_1).equals("%PDF-")||!new String(bytes,Math.max(0,bytes.length-1024),Math.min(1024,bytes.length),StandardCharsets.ISO_8859_1).contains("%%EOF"))throw new IllegalArgumentException("Anhang ist kein gültiges PDF");try{String id=UUID.randomUUID()+".pdf",safe=name.replaceAll("[^A-Za-z0-9._-]","_");return new Attachment(id,safe,bytes,hex(MessageDigest.getInstance("SHA-256").digest(bytes)));}catch(Exception e){throw new IllegalArgumentException("PDF konnte nicht geprüft werden");}}return null;}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    private void startMailWorker(){if(mailWorkerStarted)return;synchronized(this){if(mailWorkerStarted)return;Thread worker=new Thread(this::mailWorker,"freistellung-fake-mail-worker");worker.setDaemon(true);worker.start();mailWorkerStarted=true;}}
    private void mailWorker(){while(!Thread.currentThread().isInterrupted()){try{try(PreparedStatement p=db().prepareStatement("SELECT id,request_id,recipient,event,attempts,subject,body FROM freistellung_notifications WHERE state='PENDING' ORDER BY id LIMIT 20");ResultSet r=p.executeQuery()){while(r.next()){long id=r.getLong(1);String request=r.getString(2),recipient=r.getString(3),event=r.getString(4),subject=r.getString(6),body=r.getString(7);try{deliver(request,recipient,event,subject,body);markSent(id);}catch(Exception failure){markFailure(id,failure);}}}Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){getLogger().warn("Freistellung-Mailworker wartet nach Fehler",e);}}}
    private void deliver(String request,String recipient,String event,String subject,String body)throws Exception{if(subject==null||subject.isBlank())subject="Freistellungsbenachrichtigung";if(body==null)body="Bitte öffnen Sie den authentifizierten Vorgang in Arcanum.";String environment=System.getenv().getOrDefault("ARCANUM_ENVIRONMENT","dev");boolean realSmtp="prod".equalsIgnoreCase(environment)&&"smtp".equalsIgnoreCase(System.getenv().getOrDefault("ARCANUM_FREISTELLUNG_MAIL_TRANSPORT","fake"));if(realSmtp){SmtpTransport.send(recipient,subject,body);return;}Path out=Path.of(System.getenv().getOrDefault("ARCANUM_FREISTELLUNG_FAKE_MAIL_LOG","/srv/arcanum/dev/runtime/freistellung-fake-mail.log"));Path parent=out.getParent();if(parent!=null)Files.createDirectories(parent);String record=Instant.now()+" recipient="+recipient+" event="+event+" request="+request+" subject="+subject+System.lineSeparator()+body+System.lineSeparator()+"---"+System.lineSeparator();Files.writeString(out,record,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
    private void markSent(long id)throws SQLException{try(PreparedStatement u=db().prepareStatement("UPDATE freistellung_notifications SET state='SENT',sent_at=?,attempts=attempts+1,last_error=NULL WHERE id=? AND state='PENDING'")){u.setString(1,Instant.now().toString());u.setLong(2,id);u.executeUpdate();}}
    private void markFailure(long id,Exception failure)throws SQLException{String detail=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName()).replaceAll("(?i)(password|passwort|token|secret)\\s*[=:]\\s*[^\\s,;]+","$1=[redacted]");getLogger().warn("Freistellung-Mailversand fehlgeschlagen; erneuter Versuch bleibt ausstehend: "+detail);try(PreparedStatement u=db().prepareStatement("UPDATE freistellung_notifications SET attempts=attempts+1,last_error=? WHERE id=? AND state='PENDING'")){u.setString(1,detail);u.setLong(2,id);u.executeUpdate();}}
    private static int hour(int h){if(h<1||h>9)throw new IllegalArgumentException("Stunde muss zwischen 1 und 9 liegen");return h;}
    private static Map<String,String> fields(APIPostRequest r) {
        try {
            if (r.getHeader().getContentType() != null && r.getHeader().getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/")) {
                String ct=r.getHeader().getContentType(), marker="boundary="; int b=ct.toLowerCase(Locale.ROOT).indexOf(marker); if(b<0)return Map.of(); String boundary=ct.substring(b+marker.length()).trim().replace("\"","");
                String raw=new String(r.getRawBody(),StandardCharsets.ISO_8859_1), delim="--"+boundary; Map<String,String> out=new HashMap<>();
                for(String part:raw.split(java.util.regex.Pattern.quote(delim))){int sep=part.indexOf("\r\n\r\n"), name=part.indexOf("name=\""); if(sep<0||name<0)continue; int end=part.indexOf("\"",name+6); if(end<0)continue; String key=part.substring(name+6,end); String value=part.substring(sep+4); if(value.endsWith("\r\n"))value=value.substring(0,value.length()-2); if(!part.contains("filename=\""))out.put(key,value); } return out;
            }
            Map<String,Object> json=r.getJson(); if(json!=null){Map<String,String> out=new HashMap<>();json.forEach((k,v)->{if(v!=null)out.put(k,String.valueOf(v));});return out;}
        } catch(Exception ignored) { try{return r.getFormData();}catch(Exception ignoredAgain){return Map.of();} }
        return Map.of();
    }
    private static String required(APIPostRequest r,String k){String v=fields(r).get(k);if(v==null||v.isBlank())throw new IllegalArgumentException(k+" fehlt");return v.trim();}
    private static String optional(APIPostRequest r,String k){String v=fields(r).get(k);return v==null?null:v.trim();}
    private static String schoolYear(LocalDate d){int y=d.getMonthValue()>=8?d.getYear():d.getYear()-1;return y+"/"+(y+1);}
}
