package lk.gov.moh.dhp.rl.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for PatientDocument factory methods — pure logic, no Spring.
 */
class PatientDocumentTest {

    // ── fromDicom ─────────────────────────────────────────────────────────────

    @Test
    void fromDicom_standardName_splitCorrectly() {
        PatientDocument doc = PatientDocument.fromDicom("PHN001", "Perera^Kasun^M", "HIN001");
        assertThat(doc.familyName()).isEqualTo("Perera");
        assertThat(doc.givenName()).isEqualTo("Kasun");
        assertThat(doc.phn()).isEqualTo("PHN001");
    }

    @Test
    void fromDicom_fullNameComposite() {
        PatientDocument doc = PatientDocument.fromDicom("PHN001", "Perera^Kasun", "HIN001");
        assertThat(doc.fullName()).contains("Kasun").contains("Perera");
    }

    @Test
    void fromDicom_familyNameOnly() {
        PatientDocument doc = PatientDocument.fromDicom("PHN001", "Perera", "HIN001");
        assertThat(doc.familyName()).isEqualTo("Perera");
        assertThat(doc.givenName()).isNull();
    }

    @Test
    void fromDicom_null_allNameFieldsNull() {
        PatientDocument doc = PatientDocument.fromDicom("PHN001", null, "HIN001");
        assertThat(doc.givenName()).isNull();
        assertThat(doc.familyName()).isNull();
        assertThat(doc.fullName()).isNull();
    }

    @Test
    void fromDicom_blank_allNameFieldsNull() {
        PatientDocument doc = PatientDocument.fromDicom("PHN001", "  ", "HIN001");
        assertThat(doc.givenName()).isNull();
    }

    @Test
    void fromDicom_emptyComponentsNotStored() {
        // DICOM: "^Kasun^^" — familyName component is empty
        PatientDocument doc = PatientDocument.fromDicom("PHN001", "^Kasun^^", "HIN001");
        assertThat(doc.familyName()).isNull();
        assertThat(doc.givenName()).isEqualTo("Kasun");
    }

    // ── fromFhir ──────────────────────────────────────────────────────────────

    @Test
    void fromFhir_givenAndFamily() {
        PatientDocument doc = PatientDocument.fromFhir("PHN001", "Kasun", "Perera", "HIN001");
        assertThat(doc.givenName()).isEqualTo("Kasun");
        assertThat(doc.familyName()).isEqualTo("Perera");
        assertThat(doc.fullName()).contains("Kasun").contains("Perera");
    }

    @Test
    void fromFhir_nullGiven_onlyFamilyInFullName() {
        PatientDocument doc = PatientDocument.fromFhir("PHN001", null, "Perera", "HIN001");
        assertThat(doc.givenName()).isNull();
        assertThat(doc.familyName()).isEqualTo("Perera");
        assertThat(doc.fullName()).isEqualTo("Perera");
    }

    @Test
    void fromFhir_bothNull_fullNameNull() {
        PatientDocument doc = PatientDocument.fromFhir("PHN001", null, null, "HIN001");
        assertThat(doc.fullName()).isNull();
    }

    @Test
    void fromFhir_whitespaceOnlyValues_treatedAsNull() {
        PatientDocument doc = PatientDocument.fromFhir("PHN001", "  ", "  ", "HIN001");
        assertThat(doc.givenName()).isNull();
        assertThat(doc.familyName()).isNull();
    }
}
