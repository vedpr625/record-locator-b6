package lk.gov.moh.dhp.rl.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.servlet.Servlet;
import lk.gov.moh.dhp.rl.web.BinaryProvider;
import lk.gov.moh.dhp.rl.web.DocumentReferenceProvider;
import lk.gov.moh.dhp.rl.web.ListProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class FhirServerConfig {

    @Bean
    public ServletRegistrationBean<Servlet> fhirServlet(FhirContext fhirContext,
                                                          DocumentReferenceProvider documentReferenceProvider,
                                                          ListProvider listProvider,
                                                          BinaryProvider binaryProvider,
                                                          CapabilityStatementCustomizer capabilityStatementCustomizer) {
        RestfulServer server = new RestfulServer(fhirContext);
        server.setResourceProviders(List.of(documentReferenceProvider, listProvider, binaryProvider));
        server.registerInterceptor(capabilityStatementCustomizer);

        // Mount at /fhir/* so IHE MHD clients can use /fhir/DocumentReference,
        // /fhir/List, /fhir/Binary — the standard IHE base path without a
        // version segment.  (HAPI FHIR inserts the resource-type after the
        // servlet path automatically.)
        ServletRegistrationBean<Servlet> registration = new ServletRegistrationBean<>(server, "/fhir/*");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
