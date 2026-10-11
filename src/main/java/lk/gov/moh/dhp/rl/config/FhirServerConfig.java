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

        ServletRegistrationBean<Servlet> registration = new ServletRegistrationBean<>(server, "/fhir/r4/*");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
