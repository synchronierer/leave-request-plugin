package de.igslandstuhl.database.freistellung;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Minimal implicit-TLS SMTP transport; credentials are read only from environment variables. */
final class SmtpTransport {
    private SmtpTransport() {}

    static void send(String recipient, String subject, String body) throws IOException {
        String host = required("ARCANUM_FREISTELLUNG_SMTP_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("ARCANUM_FREISTELLUNG_SMTP_PORT", "465"));
        String from = required("ARCANUM_FREISTELLUNG_SMTP_FROM");
        String username = System.getenv("ARCANUM_FREISTELLUNG_SMTP_USERNAME");
        String password = System.getenv("ARCANUM_FREISTELLUNG_SMTP_PASSWORD");
        if ((username == null) != (password == null)) throw new IOException("SMTP-Zugangsdaten unvollständig");
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
            out.write("From: " + from + "\r\nTo: " + recipient + "\r\nSubject: " + header(subject) + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n");
            out.write(body.replace("\r", "").replace("\n", "\r\n").replace("\r\n.", "\r\n.."));
            out.write("\r\n.\r\n"); out.flush();
            expect(in, 250);
            command(in, out, "QUIT", 221);
        }
    }

    private static String required(String key) throws IOException { String value = System.getenv(key); if (value == null || value.isBlank()) throw new IOException(key + " fehlt"); return value; }
    private static String address(String value) throws IOException { if (!value.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")) throw new IOException("Ungültige SMTP-Adresse"); return value; }
    private static String header(String value) { return value.replaceAll("[\\r\\n]", " "); }
    private static void command(BufferedReader in, BufferedWriter out, String text, int... accepted) throws IOException { out.write(text + "\r\n"); out.flush(); int code = readCode(in); for (int expected : accepted) if (code == expected) return; throw new IOException("SMTP-Antwort " + code + " auf " + text.split(" ",2)[0]); }
    private static void expect(BufferedReader in, int expected) throws IOException { int code = readCode(in); if (code != expected) throw new IOException("SMTP-Antwort " + code); }
    private static int readCode(BufferedReader in) throws IOException { String line = in.readLine(); if (line == null || line.length() < 3) throw new IOException("SMTP-Verbindung unerwartet beendet"); int code; try { code = Integer.parseInt(line.substring(0,3)); } catch (NumberFormatException e) { throw new IOException("Ungültige SMTP-Antwort", e); } while (line.length() > 3 && line.charAt(3) == '-') { line = in.readLine(); if (line == null) throw new IOException("SMTP-Antwort unvollständig"); } return code; }
}
