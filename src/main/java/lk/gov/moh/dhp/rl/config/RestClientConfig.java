package lk.gov.moh.dhp.rl.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                // H-1: explicit timeouts prevent a hung Orthanc from stalling
                // the scheduled poller thread indefinitely.
                // connect: fail fast if the host is unreachable.
                // read: Orthanc WADO-RS study metadata can be slow on large studies.
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();
    }
}
