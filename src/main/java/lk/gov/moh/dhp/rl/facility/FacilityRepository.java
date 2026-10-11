package lk.gov.moh.dhp.rl.facility;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FacilityRepository extends JpaRepository<Facility, String> {

    List<Facility> findByProvinceCode(String provinceCode);
}
