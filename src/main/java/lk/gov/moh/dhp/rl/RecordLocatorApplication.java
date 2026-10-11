package lk.gov.moh.dhp.rl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RecordLocatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RecordLocatorApplication.class, args);
    }
}
