package lk.gov.moh.dhp.rl.facility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A hospital's own facility HIN and the province an admin has
 * assigned it to. Sri Lanka has a small, fixed number of hospitals -
 * this is a lookup table maintained by hand, not synced from
 * anywhere. It only exists so a national search can be scoped to
 * "just this province" as a plain WHERE clause.
 */
@Entity
@Table(name = "facility")
public class Facility {

    @Id
    @Column(name = "hin", length = 100)
    private String hin;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "province_code", nullable = false, length = 20)
    private String provinceCode;

    protected Facility() {
        // JPA
    }

    public Facility(String hin, String name, String provinceCode) {
        this.hin = hin;
        this.name = name;
        this.provinceCode = provinceCode;
    }

    public String hin() {
        return hin;
    }

    public String name() {
        return name;
    }

    public String provinceCode() {
        return provinceCode;
    }
}
