package io.audienceflow.api.schedule;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/schedule")
@PreAuthorize("hasAnyRole('TEACHER', 'TECHNICIAN', 'ADMIN')")
public class ScheduleController {
    private final ScheduleRepository scheduleRepository;
    private final ScheduleImportService scheduleImportService;

    public ScheduleController(ScheduleRepository scheduleRepository, ScheduleImportService scheduleImportService) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleImportService = scheduleImportService;
    }

    @GetMapping
    public List<ScheduleEntryView> entries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer buildingId,
            @RequestParam(required = false) Integer roomId,
            @RequestParam(required = false) Integer teacherId,
            @RequestParam(required = false) Integer groupId
    ) {
        return scheduleRepository.entries(resolveDate(date), buildingId, roomId, teacherId, groupId);
    }

    @GetMapping("/analytics")
    public List<ScheduleAnalyticsRow> analytics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "teacher") String dimension
    ) {
        return scheduleRepository.analytics(resolveDate(date), dimension);
    }

    @GetMapping("/directory")
    public ScheduleDirectory directory() {
        return scheduleRepository.directory();
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'ADMIN')")
    public ScheduleImportResult importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "excel") String source
    ) throws Exception {
        return scheduleImportService.importExcel(file, source);
    }

    private static LocalDate resolveDate(LocalDate date) {
        return date == null ? LocalDate.now() : date;
    }
}
