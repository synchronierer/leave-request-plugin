package de.igslandstuhl.database.freistellung;

import de.igslandstuhl.database.plugins.Plugin;
import de.igslandstuhl.database.server.Server;
import de.igslandstuhl.database.server.webserver.WebPath;
import de.igslandstuhl.database.server.webserver.ContentType;
import de.igslandstuhl.database.server.webserver.Status;
import de.igslandstuhl.database.server.webserver.access.AccessLevel;
import de.igslandstuhl.database.server.webserver.handlers.HttpHandler;
import de.igslandstuhl.database.server.webserver.requests.APIPostRequest;
import de.igslandstuhl.database.server.webserver.requests.GetRequest;
import de.igslandstuhl.database.server.webserver.requests.RequestType;
import de.igslandstuhl.database.server.webserver.responses.GetResponse;
import de.igslandstuhl.database.server.webserver.responses.PostResponse;
import de.igslandstuhl.database.server.webserver.responses.HttpResponse;
import de.igslandstuhl.database.server.resources.ResourceLocation;
import de.igslandstuhl.database.api.User;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Eigenständiges Freistellungsmodul; alle Rollenprüfungen erfolgen serverseitig. */
public final class FreistellungPlugin extends Plugin {
    static final String PERSONAL = "nach Rücksprache mit der Schulleitung";
    private FreistellungConfig config;
    private static final Set<String> TYPES = Set.of("FORTBILDUNG", "DIENSTLICHE_GRUENDE", "KLASSENFAHRT", "PERSOENLICHE_GRUENDE", "UNTERRICHTSGANG");
    private static final Set<String> DECISIONS = Set.of("GENEHMIGT", "GENEHMIGT_NACHHALTEN", "NICHT_MOEGLICH", "RUECKSPRACHE");

    @Override protected void onLoad() { config = new FreistellungConfig(this); }
    @Override protected void onEnable() {
        try { schema(); } catch (SQLException e) { throw new IllegalStateException("Freistellung-Schema konnte nicht angelegt werden", e); }
        for (String path : List.of("/freistellung-submit", "/freistellung-orga", "/freistellung-sl", "/freistellung-delete", "/freistellung-resend"))
            HttpHandler.registerPostRequestHandler(path, AccessLevel.TEACHER, this::post);
        HttpHandler.registerPostRequestHandler("/freistellung-data", AccessLevel.TEACHER, this::post);
    }
    @Override protected void onDisable() { }
    @Override public FreistellungConfig getConfig() { return config; }

    private Connection db() { return Server.getInstance().getConnection().getSQLConnection(); }
    private void schema() throws SQLException {
        try (Statement s = db().createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_requests (id TEXT PRIMARY KEY, applicant TEXT NOT NULL, type TEXT NOT NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL, start_hour INTEGER NOT NULL, end_hour INTEGER NOT NULL, reason TEXT NOT NULL, group_name TEXT, status TEXT NOT NULL, orga_decision TEXT, orga_comment TEXT, orga_user TEXT, orga_at TEXT, sl_decision TEXT, sl_internal_comment TEXT, sl_message TEXT, sl_user TEXT, sl_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT, deleted_by TEXT, school_year TEXT NOT NULL, attachment_id TEXT, attachment_name TEXT, attachment_size INTEGER, attachment_sha256 TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_history (id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL REFERENCES freistellung_requests(id), actor TEXT NOT NULL, from_status TEXT, to_status TEXT NOT NULL, event_at TEXT NOT NULL, detail TEXT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_roles (username TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('ORGALEITER','SCHULLEITUNG')), PRIMARY KEY(username, role))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS freistellung_notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL, recipient TEXT NOT NULL, event TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, sent_at TEXT)");
        }
    }

    private HttpResponse post(APIPostRequest rq) {
        User user = rq.getUser();
        if (user == null || !user.isTeacher()) return PostResponse.unauthorized(rq);
        try {
            return switch (rq.getPath().split("\\?")[0]) {
                case "/freistellung-submit" -> submit(rq, user);
                case "/freistellung-orga" -> decide(rq, user, false);
                case "/freistellung-sl" -> decide(rq, user, true);
                case "/freistellung-delete" -> delete(rq, user);
                case "/freistellung-resend" -> resend(rq, user);
                default -> data(rq, user);
            };
        } catch (IllegalArgumentException e) { return PostResponse.json(Status.BAD_REQUEST, Map.of("error", e.getMessage()), rq); }
        catch (SQLException e) { getLogger().error("Freistellung request failed", e); return PostResponse.internalServerError("Interner Fehler", rq); }
    }

    private HttpResponse submit(APIPostRequest rq, User user) throws SQLException {
        String type = required(rq,"type").toUpperCase(Locale.ROOT), start = required(rq,"startDate"), end = required(rq,"endDate");
        if (!TYPES.contains(type)) throw new IllegalArgumentException("Unbekannte Antragsart");
        int sh = hour(Integer.parseInt(required(rq,"startHour"))), eh = hour(Integer.parseInt(required(rq,"endHour")));
        LocalDate sd = LocalDate.parse(start), ed = LocalDate.parse(end);
        if (ed.isBefore(sd) || (sd.equals(ed) && eh < sh)) throw new IllegalArgumentException("Ungültiger Zeitraum");
        String reason = type.equals("PERSOENLICHE_GRUENDE") ? PERSONAL : required(rq,"reason");
        String group = optional(rq,"groupName");
        if ((type.equals("KLASSENFAHRT") || type.equals("UNTERRICHTSGANG")) && (group == null || group.isBlank())) throw new IllegalArgumentException("Lerngruppe erforderlich");
        if (!(type.equals("KLASSENFAHRT") || type.equals("UNTERRICHTSGANG"))) group = null;
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try (PreparedStatement p = db().prepareStatement("INSERT INTO freistellung_requests (id,applicant,type,start_date,end_date,start_hour,end_hour,reason,group_name,status,created_at,updated_at,school_year) VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,?)")) {
            p.setString(1,id); p.setString(2,user.getUsername()); p.setString(3,type); p.setString(4,start); p.setString(5,end); p.setInt(6,sh); p.setInt(7,eh); p.setString(8,reason); p.setString(9,group); p.setString(10,"EINGEREICHT"); p.setString(11,now); p.setString(12,now); p.setString(13,schoolYear(sd)); p.executeUpdate();
        }
        history(id,user.getUsername(),null,"EINGEREICHT",now,"Antrag eingereicht");
        return PostResponse.json(Map.of("id",id,"status","EINGEREICHT"),rq);
    }

    private HttpResponse data(APIPostRequest rq, User user) throws SQLException {
        boolean reviewer = hasRole(user,"ORGALEITER") || hasRole(user,"SCHULLEITUNG");
        String sql = reviewer ? "SELECT * FROM freistellung_requests WHERE deleted_at IS NULL ORDER BY created_at DESC" : "SELECT * FROM freistellung_requests WHERE deleted_at IS NULL AND applicant=? ORDER BY created_at DESC";
        JsonArray out = new JsonArray();
        try (PreparedStatement p = db().prepareStatement(sql)) { if (!reviewer) p.setString(1,user.getUsername()); try (ResultSet r=p.executeQuery()) { while(r.next()) out.add(publicJson(r, user, reviewer)); } }
        return PostResponse.json(out,rq);
    }

    private HttpResponse decide(APIPostRequest rq, User user, boolean sl) throws SQLException {
        String id=required(rq,"id"), decision=required(rq,"decision").toUpperCase(Locale.ROOT); if (!DECISIONS.contains(decision)) throw new IllegalArgumentException("Ungültige Bewertung");
        if (!hasRole(user,sl?"SCHULLEITUNG":"ORGALEITER")) return PostResponse.forbidden("Keine Berechtigung",rq);
        db().setAutoCommit(false); try (PreparedStatement q=db().prepareStatement("SELECT status FROM freistellung_requests WHERE id=? AND deleted_at IS NULL")) { q.setString(1,id); try(ResultSet r=q.executeQuery()){ if(!r.next()) return PostResponse.notFound("Vorgang nicht gefunden",rq); String old=r.getString(1); if(sl && !old.equals("ORGA_ABGESCHLOSSEN")) throw new IllegalArgumentException("Orga-Bearbeitung noch nicht abgeschlossen"); if(!sl && !old.equals("EINGEREICHT") && !old.equals("ORGA_RUECKSPRACHE")) throw new IllegalArgumentException("Vorgang nicht bearbeitbar"); String status=sl?"ABGESCHLOSSEN":(decision.equals("RUECKSPRACHE")?"ORGA_RUECKSPRACHE":"ORGA_ABGESCHLOSSEN"); String now=Instant.now().toString(); String sql=sl?"UPDATE freistellung_requests SET sl_decision=?,sl_internal_comment=?,sl_message=?,sl_user=?,sl_at=?,status=?,updated_at=? WHERE id=? AND status=?":"UPDATE freistellung_requests SET orga_decision=?,orga_comment=?,orga_user=?,orga_at=?,status=?,updated_at=? WHERE id=? AND status=?"; try(PreparedStatement u=db().prepareStatement(sql)){ int i=1;u.setString(i++,decision); if(sl){u.setString(i++,optional(rq,"internalComment"));u.setString(i++,optional(rq,"message"));u.setString(i++,user.getUsername());u.setString(i++,now);}else{u.setString(i++,optional(rq,"comment"));u.setString(i++,user.getUsername());u.setString(i++,now);}u.setString(i++,status);u.setString(i++,now);u.setString(i++,id);u.setString(i,old); if(u.executeUpdate()!=1) throw new IllegalArgumentException("Vorgang wurde inzwischen bearbeitet");} history(id,user.getUsername(),old,status,now,decision); if(sl) queue(id,"ANTRAGSTELLER","ABSCHLUSS"); else queue(id,"SCHULLEITUNG","ORGA_ABGESCHLOSSEN"); db().commit(); return PostResponse.json(Map.of("id",id,"status",status),rq); }} catch(Throwable t){try{db().rollback();}catch(SQLException ignored){} if(t instanceof SQLException e)throw e; if(t instanceof IllegalArgumentException e)throw e; throw new SQLException(t);} finally{db().setAutoCommit(true);}
    }

    private HttpResponse delete(APIPostRequest rq, User user) throws SQLException { if(!(hasRole(user,"ORGALEITER")||hasRole(user,"SCHULLEITUNG"))) return PostResponse.forbidden("Keine Berechtigung",rq); String id=required(rq,"id"),now=Instant.now().toString(); try(PreparedStatement p=db().prepareStatement("UPDATE freistellung_requests SET deleted_at=?,deleted_by=?,updated_at=? WHERE id=? AND deleted_at IS NULL")){p.setString(1,now);p.setString(2,user.getUsername());p.setString(3,now);p.setString(4,id);if(p.executeUpdate()!=1)return PostResponse.notFound("Vorgang nicht gefunden",rq);}history(id,user.getUsername(),null,"GELOESCHT",now,"Revisionsfähige Löschung");return PostResponse.json(Map.of("deleted",true),rq); }
    private HttpResponse resend(APIPostRequest rq, User user) throws SQLException { if(!(hasRole(user,"ORGALEITER")||hasRole(user,"SCHULLEITUNG")))return PostResponse.forbidden("Keine Berechtigung",rq);try(PreparedStatement p=db().prepareStatement("UPDATE freistellung_notifications SET state='PENDING', attempts=attempts+1 WHERE id=?")){p.setLong(1,Integer.parseInt(required(rq,"notificationId")));p.executeUpdate();}return PostResponse.json(Map.of("queued",true),rq); }

    private JsonObject publicJson(ResultSet r, User user, boolean reviewer) throws SQLException { JsonObject o=new JsonObject(); o.addProperty("id",r.getString("id"));o.addProperty("applicant",r.getString("applicant"));o.addProperty("type",r.getString("type"));o.addProperty("startDate",r.getString("start_date"));o.addProperty("endDate",r.getString("end_date"));o.addProperty("startHour",r.getInt("start_hour"));o.addProperty("endHour",r.getInt("end_hour"));o.addProperty("reason",r.getString("reason"));o.addProperty("groupName",r.getString("group_name"));o.addProperty("status",r.getString("status"));o.addProperty("createdAt",r.getString("created_at"));o.addProperty("completedAt",r.getString("sl_at"));if(reviewer){o.addProperty("orgaDecision",r.getString("orga_decision"));o.addProperty("orgaComment",r.getString("orga_comment"));o.addProperty("slInternalComment",r.getString("sl_internal_comment"));o.addProperty("slMessage",r.getString("sl_message"));}else{o.addProperty("slDecision",r.getString("sl_decision"));o.addProperty("slMessage",r.getString("sl_message"));}return o; }
    private boolean hasRole(User u,String role)throws SQLException{try(PreparedStatement p=db().prepareStatement("SELECT 1 FROM freistellung_roles WHERE username=? AND role=?")){p.setString(1,u.getUsername());p.setString(2,role);try(ResultSet r=p.executeQuery()){return r.next();}}}
    private void history(String id,String actor,String from,String to,String at,String detail)throws SQLException{try(PreparedStatement p=db().prepareStatement("INSERT INTO freistellung_history(request_id,actor,from_status,to_status,event_at,detail) VALUES(?,?,?,?,?,?)")){p.setString(1,id);p.setString(2,actor);p.setString(3,from);p.setString(4,to);p.setString(5,at);p.setString(6,detail);p.executeUpdate();}}
    private void queue(String id,String recipient,String event)throws SQLException{try(PreparedStatement p=db().prepareStatement("INSERT INTO freistellung_notifications(request_id,recipient,event,state,created_at) VALUES(?,?,?,?,?)")){p.setString(1,id);p.setString(2,recipient);p.setString(3,event);p.setString(4,"PENDING");p.setString(5,Instant.now().toString());p.executeUpdate();}}
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
