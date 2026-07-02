package io.audienceflow.api.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ScheduleImportServiceTest {

    @Test
    void importsLgtuWideScheduleWorkbook() throws Exception {
        CapturingScheduleRepository repository = new CapturingScheduleRepository();
        ScheduleImportService service = new ScheduleImportService(repository);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "ikn-bak.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                wideScheduleWorkbook()
        );

        ScheduleImportResult result = service.importExcel(file, "ikn-bak.xlsx");

        List<ScheduleRepository.ImportedScheduleRow> rows = repository.importedRows;

        assertThat(result.parsedRows()).isEqualTo(2);
        assertThat(result.importedRows()).isEqualTo(2);
        assertThat(result.skippedRows()).isZero();
        assertThat(repository.source).isEqualTo("ikn-bak.xlsx");
        assertThat(rows).hasSize(2);

        ScheduleRepository.ImportedScheduleRow first = rows.getFirst();
        assertThat(first.groupName()).isEqualTo("ПИ-25-1");
        assertThat(first.teacherName()).isEqualTo("Ткаченко С.В.");
        assertThat(first.disciplineName()).isEqualTo("Дискретная математика");
        assertThat(first.roomName()).isEqualTo("9-408");
        assertThat(first.building()).isEqualTo("Корпус 9");
        assertThat(first.floor()).isEqualTo("4");
        assertThat(first.lessonType()).isEqualTo("практика");
        assertThat(first.weekday()).isEqualTo(1);
        assertThat(first.startsAt()).isEqualTo(LocalTime.of(13, 20));
        assertThat(first.endsAt()).isEqualTo(LocalTime.of(14, 50));
        assertThat(first.validFrom()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(first.validTo()).isEqualTo(LocalDate.of(2026, 8, 31));

        ScheduleRepository.ImportedScheduleRow second = rows.get(1);
        assertThat(second.groupName()).isEqualTo("АС-25-1");
        assertThat(second.teacherName()).isEqualTo("Подлесных Д.А.");
        assertThat(second.roomName()).isEqualTo("436");
        assertThat(second.building()).isEqualTo("Корпус 1");
        assertThat(second.lessonType()).isEqualTo("лабораторная");
    }

    private byte[] wideScheduleWorkbook() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Table 1");
            sheet.createRow(0).createCell(5).setCellValue("РАСПИСАНИЕ ЗАНЯТИЙ 1 КУРСА ИКН\nвесенний семестр 2025-2026 учебного года");

            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("День недели");
            header.createCell(1).setCellValue("Время начала\nзанятий");
            header.createCell(2).setCellValue("Группа ПИ-25-1");
            header.createCell(3).setCellValue("Аудито рия");
            header.createCell(4).setCellValue("Группа АС-25-1");
            header.createCell(6).setCellValue("Аудито рия");

            Row lesson = sheet.createRow(2);
            lesson.createCell(0).setCellValue("Понедельник");
            lesson.createCell(1).setCellValue("13:20-14:50");
            lesson.createCell(2).setCellValue("Дискретная математика ст.пр. Ткаченко С.В.");
            lesson.createCell(3).setCellValue("9-408\nпр.");
            lesson.createCell(4).setCellValue("Физика\nдоц. Подлесных Д.А.");
            lesson.createCell(6).setCellValue("436\nлаб.");

            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static class CapturingScheduleRepository extends ScheduleRepository {
        private List<ImportedScheduleRow> importedRows = new ArrayList<>();
        private String source = "";

        CapturingScheduleRepository() {
            super(null);
        }

        @Override
        public int importRows(List<ImportedScheduleRow> rows, String source) {
            this.importedRows = new ArrayList<>(rows);
            this.source = source;
            return rows.size();
        }
    }
}
