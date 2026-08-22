package ng.unilag.mediqueue.repository;

import ng.unilag.mediqueue.model.Department;

import java.util.List;
import java.util.Optional;

/** Storage operations for clinic departments (Project.md section 7). */
public interface DepartmentRepository {

    Department save(Department department);

    Department update(Department department);

    Optional<Department> findById(long id);

    Optional<Department> findByName(String name);

    /** All departments, including deactivated ones. For the admin screen. */
    List<Department> findAll();

    /** Only departments currently accepting bookings. For the patient screen. */
    List<Department> findActive();

    void deleteById(long id);

    long count();
}
