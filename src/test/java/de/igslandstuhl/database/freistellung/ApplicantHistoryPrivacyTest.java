package de.igslandstuhl.database.freistellung;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ApplicantHistoryPrivacyTest {
    @Test
    void applicantApiAllowListRemovesInternalHistoryMetadata() {
        JsonObject internal = JsonParser.parseString("""
                {
                  "id":"synthetic-request",
                  "applicant":"synthetic-applicant@example.invalid",
                  "status":"ABGESCHLOSSEN",
                  "slDecision":"GENEHMIGT",
                  "slMessage":"Bitte im Kollegium beachten.",
                  "orgaDecision":"GENEHMIGT_NACHHALTEN",
                  "orgaComment":"Interne Orga-Mitteilung an die Schulleitung",
                  "slInternalComment":"Interne Schulleitungs-Mitteilung an die Orga",
                  "history":[{
                    "actor":"synthetic-orga@example.invalid",
                    "function":"ORGALEITUNG",
                    "fromStatus":"EINGEREICHT",
                    "toStatus":"ORGA_ABGESCHLOSSEN",
                    "at":"2026-10-31T10:00:00Z",
                    "detail":"GENEHMIGT"
                  }]
                }
                """).getAsJsonObject();

        JsonObject applicant = FreistellungPlugin.applicantView(internal);

        assertEquals("Entschieden", applicant.get("status").getAsString());
        assertEquals("Genehmigt", applicant.get("slDecision").getAsString());
        assertEquals("Bitte im Kollegium beachten.", applicant.get("slMessage").getAsString());
        assertFalse(applicant.has("orgaDecision"));
        assertFalse(applicant.has("orgaComment"));
        assertFalse(applicant.has("slInternalComment"));

        JsonObject history = applicant.getAsJsonArray("history").get(0).getAsJsonObject();
        assertEquals("2026-10-31T10:00:00Z", history.get("at").getAsString());
        assertFalse(history.has("actor"));
        assertFalse(history.has("function"));
        assertFalse(history.has("fromStatus"));
        assertFalse(history.has("toStatus"));
        assertFalse(history.has("detail"));
    }
}
