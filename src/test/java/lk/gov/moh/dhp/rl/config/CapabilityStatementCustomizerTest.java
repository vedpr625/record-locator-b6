package lk.gov.moh.dhp.rl.config;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for CapabilityStatementCustomizer.
 * Tests that the HAPI @Hook method correctly sets software name/version,
 * publisher, and adds the IHE MHD Document Responder profile instantiation.
 */
class CapabilityStatementCustomizerTest {

    CapabilityStatementCustomizer customizer;

    @BeforeEach
    void setUp() {
        customizer = new CapabilityStatementCustomizer();
    }

    @Test
    void customize_setsSoftwareName() {
        CapabilityStatement statement = new CapabilityStatement();

        customizer.customize(statement);

        assertThat(statement.getSoftware().getName()).isEqualTo("DHP Record Locator");
    }

    @Test
    void customize_setsSoftwareVersion() {
        CapabilityStatement statement = new CapabilityStatement();

        customizer.customize(statement);

        assertThat(statement.getSoftware().getVersion()).isEqualTo("2.0.0");
    }

    @Test
    void customize_setsPublisher() {
        CapabilityStatement statement = new CapabilityStatement();

        customizer.customize(statement);

        assertThat(statement.getPublisher()).isEqualTo("Ministry of Health, Sri Lanka");
    }

    @Test
    void customize_addsMhdDocumentResponderInstantiates() {
        CapabilityStatement statement = new CapabilityStatement();

        customizer.customize(statement);

        boolean hasProfile = statement.getInstantiates().stream()
                .anyMatch(url -> url.getValue().contains("IHE.MHD.DocumentResponder"));
        assertThat(hasProfile).isTrue();
    }

    @Test
    void customize_nonCapabilityStatement_doesNothing() {
        // Should silently return without throwing when called with a non-CapabilityStatement
        IBaseConformance other = mock(IBaseConformance.class);
        // Must not throw
        customizer.customize(other);
    }

    @Test
    void customize_setsAllFieldsTogether() {
        CapabilityStatement statement = new CapabilityStatement();

        customizer.customize(statement);

        assertThat(statement.getSoftware().getName()).isEqualTo("DHP Record Locator");
        assertThat(statement.getSoftware().getVersion()).isEqualTo("2.0.0");
        assertThat(statement.getPublisher()).isEqualTo("Ministry of Health, Sri Lanka");
        assertThat(statement.getInstantiates()).isNotEmpty();
    }
}
