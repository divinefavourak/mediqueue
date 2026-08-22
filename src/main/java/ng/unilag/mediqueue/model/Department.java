package ng.unilag.mediqueue.model;

import java.time.LocalTime;

/**
 * A clinic department that runs its own daily queue (Project.md 4.2, section 7).
 *
 * <p>Opening hours and capacity are what make constraint 10.3 -- "patients may only book
 * during available clinic schedules" -- enforceable rather than aspirational.
 */
public class Department {

    private Long id;
    private String name;
    private LocalTime opensAt;
    private LocalTime closesAt;
    private int dailyCapacity;
    private boolean active;

    public Department() {
    }

    public Department(Long id, String name, LocalTime opensAt, LocalTime closesAt,
                      int dailyCapacity, boolean active) {
        this.id = id;
        this.name = name;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
        this.dailyCapacity = dailyCapacity;
        this.active = active;
    }

    /** True when the given time falls inside opening hours. */
    public boolean isOpenAt(LocalTime time) {
        return !time.isBefore(opensAt) && time.isBefore(closesAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalTime getOpensAt() {
        return opensAt;
    }

    public void setOpensAt(LocalTime opensAt) {
        this.opensAt = opensAt;
    }

    public LocalTime getClosesAt() {
        return closesAt;
    }

    public void setClosesAt(LocalTime closesAt) {
        this.closesAt = closesAt;
    }

    public int getDailyCapacity() {
        return dailyCapacity;
    }

    public void setDailyCapacity(int dailyCapacity) {
        this.dailyCapacity = dailyCapacity;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return "Department{id=" + id + ", name='" + name + "'}";
    }
}
