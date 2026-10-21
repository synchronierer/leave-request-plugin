package de.igslandstuhl.database.freistellung;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

/** Minimal implicit-TLS SMTP transport; credentials remain outside source and release artifacts. */
final class SmtpTransport {
    private SmtpTransport() {}

    static void send(String recipient, String subject, String body) throws IOException {
        Settings settings = settings();
        String host = settings.host;
        int port = settings.port;
        String from = settings.from;
        String username = settings.username;
        String password = settings.password;
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        socket.setSoTimeout(10000);
        try (socket; BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))) {
            expect(in, 220); command(in, out, "EHLO arcanum-dev", 250);
            if (username != null) {
                command(in, out, "AUTH LOGIN", 334);
                command(in, out, Base64.getEncoder().encodeToString(username.getBytes(StandardCharsets.US_ASCII)), 334);
                command(in, out, Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)), 235);
            }
            command(in, out, "MAIL FROM:<" + address(from) + ">", 250);
            command(in, out, "RCPT TO:<" + address(recipient) + ">", 250, 251);
            command(in, out, "DATA", 354);
            out.write("From: " + displayName(settings.displayName) + " <" + from + ">\r\nTo: " + recipient + "\r\nSubject: " + header(subject) + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n");
            out.write(body.replace("\r", "").replace("\n", "\r\n").replace("\r\n.", "\r\n.."));
            out.write("\r\n.\r\n"); out.flush();
            expect(in, 250);
            command(in, out, "QUIT", 221);
        }
    }

    private static Settings settings() throws IOException {
        String configPath = System.getenv("ARCANUM_FREISTELLUNG_SMTP_CONFIG");
        if (configPath != null && !configPath.isBlank()) return fromFile(Path.of(configPath));
        return fromEnvironment();
    }

    private static Settings fromFile(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("SMTP-Konfigurationsdatei fehlt");
        try {
            EnumSet<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(absolute));
            if (permissions.contains(PosixFilePermission.GROUP_READ) || permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.GROUP_EXECUTE) || permissions.contains(PosixFilePermission.OTHERS_READ) || permissions.contains(PosixFilePermission.OTHERS_WRITE) || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) throw new IOException("SMTP-Konfigurationsdatei ist nicht restriktiv geschützt");
        } catch (UnsupportedOperationException ignored) { throw new IOException("SMTP-Konfigurationsdatei benötigt POSIX-Dateirechte"); }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(absolute, StandardCharsets.UTF_8)) { p.load(in); }
        String tls = p.getProperty("tls", "implicit");
        if (!"implicit".equalsIgnoreCase(tls)) throw new IOException("Nur implizites TLS ist zulässig");
        return new Settings(required(p, "host"), port(p.getProperty("port", "465")), required(p, "from"), required(p, "username"), required(p, "password"), p.getProperty("displayName", "Arcanum – Freistellungen"));
    }

    private static Settings fromEnvironment() throws IOException {
        String username = System.getenv("ARCANUM_FREISTELLUNG_SMTP_USERNAME");
        String password = System.getenv("ARCANUM_FREISTELLUNG_SMTP_PASSWORD");
        if ((username == null) != (password == null)) throw new IOException("SMTP-Zugangsdaten unvollständig");
        return new Settings(requiredEnv("ARCANUM_FREISTELLUNG_SMTP_HOST"), port(System.getenv().getOrDefault("ARCANUM_FREISTELLUNG_SMTP_PORT", "465")), requiredEnv("ARCANUM_FREISTELLUNG_SMTP_FROM"), requiredValue(username, "ARCANUM_FREISTELLUNG_SMTP_USERNAME"), requiredValue(password, "ARCANUM_FREISTELLUNG_SMTP_PASSWORD"), System.getenv().getOrDefault("ARCANUM_FREISTELLUNG_SMTP_DISPLAY_NAME", "Arcanum – Freistellungen"));
    }

    private static int port(String value) throws IOException { try { int port = Integer.parseInt(value); if (port < 1 || port > 65535) throw new IOException("Ungültiger SMTP-Port"); return port; } catch (NumberFormatException e) { throw new IOException("Ungültiger SMTP-Port", e); } }
    private static String required(Properties p, String key) throws IOException { return requiredValue(p.getProperty(key), key); }
    private static String requiredEnv(String key) throws IOException { return requiredValue(System.getenv(key), key); }
    private static String requiredValue(String value, String key) throws IOException { if (value == null || value.isBlank()) throw new IOException(key + " fehlt"); return value; }
    private static String displayName(String value) { return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?="; }

    private record Settings(String host, int port, String from, String username, String password, String displayName) {}

    private static String required(String key) throws IOException { String value = System.getenv(key); if (value == null || value.isBlank()) throw new IOException(key + " fehlt"); return value; }
    private static String address(String value) throws IOException { if (!value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")) throw new IOException("Ungültige SMTP-Adresse"); return value; }
    private static String header(String value) { return value.replaceAll("[\\r\\n]", " "); }
    private static void command(BufferedReader in, BufferedWriter out, String text, int... accepted) throws IOException { out.write(text + "\r\n"); out.flush(); int code = readCode(in); for (int expected : accepted) if (code == expected) return; throw new IOException("SMTP-Antwort " + code + " auf " + text.split(" ",2)[0]); }
    private static void expect(BufferedReader in, int expected) throws IOException { int code = readCode(in); if (code != expected) throw new IOException("SMTP-Antwort " + code); }
    private static int readCode(BufferedReader in) throws IOException { String line = in.readLine(); if (line == null || line.length() < 3) throw new IOException("SMTP-Verbindung unerwartet beendet"); int code; try { code = Integer.parseInt(line.substring(0,3)); } catch (NumberFormatException e) { throw new IOException("Ungültige SMTP-Antwort", e); } while (line.length() > 3 && line.charAt(3) == '-') { line = in.readLine(); if (line == null) throw new IOException("SMTP-Antwort unvollständig"); } return code; }
}
