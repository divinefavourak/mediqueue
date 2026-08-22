package ng.unilag.mediqueue.service;

import ng.unilag.mediqueue.exception.ValidationException;
import ng.unilag.mediqueue.repository.ReportRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * The reports required by Project.md 4.4.
 *
 * <p>Thin by design: the counting belongs in SQL, so this class validates the date range
 * and delegates. A service that loaded rows and tallied them in Java would be slower and
 * would duplicate logic the database already has.
 */
public final class ReportService {

    /** Bounded so one request cannot ask the database to scan years of history. */
    private static final int MAX_RANGE_DAYS = 366;

    private final ReportRepository reports;

    public ReportService(ReportRepository reports) {
        this.reports = reports;
    }

    public ReportRepository.DailyAttendance dailyAttendance(LocalDate date) {
        return reports.dailyAttendance(date == null ? LocalDate.now() : date);
    }

    public List<ReportRepository.Row> patientsByDepartment(LocalDate from, LocalDate to) {
        validateRange(from, to);
        return reports.patientsByDepartment(from, to);
    }

    public List<ReportRepository.Row> peakPeriods(LocalDate from, LocalDate to) {
        validateRange(from, to);
        return reports.peakPeriods(from, to);
    }

    public List<ReportRepository.Row> noShows(LocalDate from, LocalDate to) {
        validateRange(from, to);
        return reports.noShowsByDepartment(from, to);
    }

    public ReportRepository.Totals totals() {
        return reports.totals(LocalDate.now());
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ValidationException("Both a start and an end date are required.");
        }
        if (from.isAfter(to)) {
            throw new ValidationException("The start date must not be after the end date.");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new ValidationException("Report range cannot exceed " + MAX_RANGE_DAYS + " days.");
        }
    }
}
