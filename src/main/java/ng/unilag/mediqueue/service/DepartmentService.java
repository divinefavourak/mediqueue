package ng.unilag.mediqueue.service;

import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.exception.ValidationException;
import ng.unilag.mediqueue.model.Department;
import ng.unilag.mediqueue.repository.DepartmentRepository;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Department administration (Project.md 3.3, section 7). */
public final class DepartmentService {

    private final DepartmentRepository departments;

    public DepartmentService(DepartmentRepository departments) {
        this.departments = departments;
    }

    public List<Department> listAll() {
        return departments.findAll();
    }

    /** Only departments open for booking -- what the patient booking screen shows. */
    public List<Department> listActive() {
        return departments.findActive();
    }

    public Department require(long id) {
        return departments.findById(id)
                .orElseThrow(() -> new NotFoundException("That department does not exist."));
    }

    public Department create(String name, String opensAt, String closesAt, int dailyCapacity) {
        String cleanName = validateName(name);
        departments.findByName(cleanName).ifPresent(existing -> {
            throw new ValidationException("A department called " + cleanName + " already exists.");
        });
        LocalTime opens = parseTime(opensAt, "Opening time");
        LocalTime closes = parseTime(closesAt, "Closing time");
        validateHours(opens, closes);
        validateCapacity(dailyCapacity);

        return departments.save(new Department(null, cleanName, opens, closes, dailyCapacity, true));
    }

    public Department update(long id, String name, String opensAt, String closesAt,
                            int dailyCapacity, boolean active) {
        Department department = require(id);
        String cleanName = validateName(name);

        // A rename may not collide with a different department's name.
        departments.findByName(cleanName).ifPresent(other -> {
            if (!other.getId().equals(id)) {
                throw new ValidationException("A department called " + cleanName + " already exists.");
            }
        });

        LocalTime opens = parseTime(opensAt, "Opening time");
        LocalTime closes = parseTime(closesAt, "Closing time");
        validateHours(opens, closes);
        validateCapacity(dailyCapacity);

        department.setName(cleanName);
        department.setOpensAt(opens);
        department.setClosesAt(closes);
        department.setDailyCapacity(dailyCapacity);
        department.setActive(active);
        return departments.update(department);
    }

    /**
     * Takes a department out of service without deleting it.
     *
     * <p>Preferred over deletion because appointment history references the department;
     * removing the row would orphan or destroy past records the reports depend on.
     */
    public Department deactivate(long id) {
        Department department = require(id);
        department.setActive(false);
        return departments.update(department);
    }

    private String validateName(String name) {
        if (name == null || name.trim().length() < 2) {
            throw new ValidationException("Department name is required.");
        }
        if (name.trim().length() > 100) {
            throw new ValidationException("Department name must be 100 characters or fewer.");
        }
        return name.trim();
    }

    private LocalTime parseTime(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(label + " is required.");
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException(label + " must look like 08:00.");
        }
    }

    private void validateHours(LocalTime opens, LocalTime closes) {
        if (!opens.isBefore(closes)) {
            throw new ValidationException("Opening time must be earlier than closing time.");
        }
    }

    private void validateCapacity(int dailyCapacity) {
        if (dailyCapacity < 1 || dailyCapacity > 500) {
            throw new ValidationException("Daily capacity must be between 1 and 500.");
        }
    }
}
