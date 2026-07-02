package io.audienceflow.api.schedule;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/campus")
@PreAuthorize("hasAnyRole('TEACHER', 'TECHNICIAN', 'ADMIN')")
public class CampusController {
    private final ScheduleRepository scheduleRepository;

    public CampusController(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    @GetMapping("/buildings")
    public List<CampusBuilding> buildings() {
        return scheduleRepository.buildings();
    }
}
