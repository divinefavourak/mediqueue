package ng.unilag.mediqueue.web.handler;

import com.sun.net.httpserver.HttpExchange;
import ng.unilag.mediqueue.exception.NotFoundException;
import ng.unilag.mediqueue.model.Role;
import ng.unilag.mediqueue.repository.ReportRepository;
import ng.unilag.mediqueue.service.ReportService;
import ng.unilag.mediqueue.web.support.ApiHandler;
import ng.unilag.mediqueue.web.support.HttpExchanges;
import ng.unilag.mediqueue.web.support.Json;
import ng.unilag.mediqueue.web.support.SessionStore;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * /api/reports/* -- the four reports in Project.md 4.4.
 *
 * <p>Restricted to staff and administrators: these are aggregates over the whole clinic's
 * activity, which no patient should see.
 */
public final class ReportHandler extends ApiHandler {

    private final ReportService reports;

    public ReportHandler(SessionStore sessions, ReportService reports) {
        super(sessions, Role.STAFF, Role.ADMIN);
        this.reports = reports;
    }

    @Override
    protected void process(HttpExchange exchange) throws IOException {
        requireMethod(exchange, "GET");
        String report = HttpExchanges.pathSegment(exchange, 2).orElse("");
        Map<String, String> query = HttpExchanges.queryParams(exchange);

        switch (report) {
            case "daily-attendance" -> dailyAttendance(exchange, query);
            case "by-department" -> rows(exchange, query, reports::patientsByDepartment, "Patients by department");
            case "peak-periods" -> rows(exchange, query, reports::peakPeriods, "Attendance by hour");
            case "no-shows" -> rows(exchange, query, reports::noShows, "No-shows by department");
            case "totals" -> totals(exchange);
            default -> throw new NotFoundException("Unknown report: " + report);
        }
    }

    /** Daily patient attendance (4.4, bullet 1). Defaults to today. */
    private void dailyAttendance(HttpExchange exchange, Map<String, String> query) throws IOException {
        LocalDate date = HttpExchanges.optionalDate(query, "date", LocalDate.now());
        ReportRepository.DailyAttendance report = reports.dailyAttendance(date);

        HttpExchanges.sendOk(exchange, Json.object()
                .put("date", report.date())
                .put("booked", report.booked())
                .put("attended", report.attended())
                .put("skipped", report.skipped())
                .put("cancelled", report.cancelled())
                .put("stillWaiting", report.stillWaiting())
                .put("attendanceRate", Math.round(report.attendanceRate() * 10) / 10.0)
                .toJson());
    }

    /** Shared shape for the three label/value reports. */
    private void rows(HttpExchange exchange, Map<String, String> query,
                      RangeReport source, String title) throws IOException {
        // A month either side of today. The range must reach forwards as well as back:
        // appointments are booked for future dates, so a history-only default would
        // report zero for every upcoming clinic day.
        LocalDate to = HttpExchanges.optionalDate(query, "to", LocalDate.now().plusDays(30));
        LocalDate from = HttpExchanges.optionalDate(query, "from", LocalDate.now().minusDays(30));

        List<ReportRepository.Row> data = source.run(from, to);
        long total = data.stream().mapToLong(ReportRepository.Row::value).sum();

        HttpExchanges.sendOk(exchange, Json.object()
                .put("title", title)
                .put("from", from)
                .put("to", to)
                .put("total", total)
                .putRaw("rows", Json.array(data, row -> Json.object()
                        .put("label", row.label())
                        .put("value", row.value())))
                .toJson());
    }

    /** Headline figures for the admin dashboard. */
    private void totals(HttpExchange exchange) throws IOException {
        ReportRepository.Totals totals = reports.totals();
        HttpExchanges.sendOk(exchange, Json.object()
                .put("patients", totals.patients())
                .put("staff", totals.staff())
                .put("departments", totals.departments())
                .put("appointmentsToday", totals.appointmentsToday())
                .put("waitingNow", totals.waitingNow())
                .toJson());
    }

    /** Lets the three range-based reports share one code path. */
    @FunctionalInterface
    private interface RangeReport {
        List<ReportRepository.Row> run(LocalDate from, LocalDate to);
    }
}
