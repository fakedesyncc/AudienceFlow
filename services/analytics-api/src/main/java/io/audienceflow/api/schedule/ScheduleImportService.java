package io.audienceflow.api.schedule;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ScheduleImportService {
    private static final DateTimeFormatter DATE_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("[d.M.uuuu][dd.MM.uuuu][uuuu-MM-dd]")
            .parseDefaulting(ChronoField.ERA, 1)
            .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("[H:mm][HH:mm][H.mm][HH.mm]")
            .toFormatter(Locale.ROOT);

    private final ScheduleRepository scheduleRepository;
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public ScheduleImportService(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    public ScheduleImportResult importExcel(MultipartFile file, String source) throws IOException {
        if (file.isEmpty()) {
            return new ScheduleImportResult(0, 0, 0, List.of("Файл пустой"));
        }
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Header header = findHeader(sheet);
            if (header == null) {
                return new ScheduleImportResult(0, 0, 0, List.of("Не найдена строка заголовков"));
            }
            List<ScheduleRepository.ImportedScheduleRow> rows = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlank(row)) {
                    continue;
                }
                try {
                    rows.add(parseRow(row, header.columns()));
                } catch (IllegalArgumentException exception) {
                    warnings.add("Строка " + (rowIndex + 1) + ": " + exception.getMessage());
                }
            }
            int imported = scheduleRepository.importRows(rows, blankTo(source, "excel"));
            return new ScheduleImportResult(rows.size(), imported, warnings.size(), warnings.stream().limit(20).toList());
        }
    }

    private Header findHeader(Sheet sheet) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), 40); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            Map<String, Integer> columns = new HashMap<>();
            for (Cell cell : row) {
                String key = canonical(cellText(cell));
                alias(key).forEach(alias -> columns.putIfAbsent(alias, cell.getColumnIndex()));
            }
            if (columns.containsKey("group") && columns.containsKey("teacher")
                    && columns.containsKey("discipline") && columns.containsKey("room")) {
                return new Header(rowIndex, columns);
            }
        }
        return null;
    }

    private ScheduleRepository.ImportedScheduleRow parseRow(Row row, Map<String, Integer> columns) {
        String group = required(row, columns, "group", "группа");
        String teacher = required(row, columns, "teacher", "преподаватель");
        String discipline = required(row, columns, "discipline", "дисциплина");
        String room = required(row, columns, "room", "аудитория");
        String building = value(row, columns, "building");
        String floor = value(row, columns, "floor");
        String institute = value(row, columns, "institute");
        String department = value(row, columns, "department");
        String lessonType = blankTo(value(row, columns, "lessonType"), "занятие");
        String subgroup = value(row, columns, "subgroup");
        int capacity = parsePositiveInt(value(row, columns, "capacity"), 30);
        LocalDate date = parseDate(row, columns.get("date"));
        int weekday = date == null ? parseWeekday(required(row, columns, "weekday", "день недели")) : date.getDayOfWeek().getValue();
        LocalTime[] range = parseTimeRange(row, columns);
        LocalDate validFrom = date == null ? LocalDate.now().minusMonths(1) : date;
        LocalDate validTo = date == null ? null : date;
        return new ScheduleRepository.ImportedScheduleRow(
                group,
                institute,
                teacher,
                department,
                discipline,
                room,
                building,
                floor,
                capacity,
                date,
                weekday,
                parseWeekType(value(row, columns, "weekType")),
                range[0],
                range[1],
                lessonType,
                subgroup,
                validFrom,
                validTo
        );
    }

    private LocalTime[] parseTimeRange(Row row, Map<String, Integer> columns) {
        String start = value(row, columns, "start");
        String end = value(row, columns, "end");
        if (!start.isBlank() && !end.isBlank()) {
            return new LocalTime[]{parseTime(start), parseTime(end)};
        }
        String range = value(row, columns, "time").replace('–', '-').replace('—', '-');
        String[] parts = range.split("-");
        if (parts.length == 2) {
            return new LocalTime[]{parseTime(parts[0]), parseTime(parts[1])};
        }
        throw new IllegalArgumentException("не указано время занятия");
    }

    private LocalDate parseDate(Row row, Integer columnIndex) {
        if (columnIndex == null) {
            return null;
        }
        Cell cell = row.getCell(columnIndex);
        if (cell == null) {
            return null;
        }
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String value = cellText(cell);
        if (value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("некорректная дата: " + value);
        }
    }

    private LocalTime parseTime(String value) {
        String normalized = value.trim().replace('.', ':');
        try {
            return LocalTime.parse(normalized, TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("некорректное время: " + value);
        }
    }

    private int parseWeekday(String value) {
        String normalized = canonical(value);
        return switch (normalized) {
            case "понедельник", "пн", "mon", "monday" -> 1;
            case "вторник", "вт", "tue", "tuesday" -> 2;
            case "среда", "ср", "wed", "wednesday" -> 3;
            case "четверг", "чт", "thu", "thursday" -> 4;
            case "пятница", "пт", "fri", "friday" -> 5;
            case "суббота", "сб", "sat", "saturday" -> 6;
            case "воскресенье", "вс", "sun", "sunday" -> 7;
            default -> throw new IllegalArgumentException("некорректный день недели: " + value);
        };
    }

    private String parseWeekType(String value) {
        String normalized = canonical(value);
        return switch (normalized) {
            case "белая", "бел", "бн", "white" -> "white";
            case "зеленая", "зел", "зн", "green" -> "green";
            case "четная", "even" -> "even";
            case "нечетная", "odd" -> "odd";
            default -> "any";
        };
    }

    private int parsePositiveInt(String value, int fallback) {
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.replaceAll("[^0-9]", "")));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String required(Row row, Map<String, Integer> columns, String key, String label) {
        String value = value(row, columns, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("пустое поле \"" + label + "\"");
        }
        return value;
    }

    private String value(Row row, Map<String, Integer> columns, String key) {
        Integer columnIndex = columns.get(key);
        if (columnIndex == null) {
            return "";
        }
        return cellText(row.getCell(columnIndex)).trim();
    }

    private boolean isBlank(Row row) {
        for (Cell cell : row) {
            if (!cellText(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String cellText(Cell cell) {
        return cell == null ? "" : formatter.formatCellValue(cell);
    }

    private List<String> alias(String key) {
        return switch (key) {
            case "группа", "studentgroup", "group", "группы" -> List.of("group");
            case "институт", "факультет", "institute" -> List.of("institute");
            case "преподаватель", "фиопреподавателя", "teacher", "fio" -> List.of("teacher");
            case "кафедра", "department" -> List.of("department");
            case "дисциплина", "предмет", "discipline", "subject" -> List.of("discipline");
            case "аудитория", "ауд", "room", "classroom" -> List.of("room");
            case "корпус", "building" -> List.of("building");
            case "этаж", "floor" -> List.of("floor");
            case "вместимость", "capacity" -> List.of("capacity");
            case "дата", "date" -> List.of("date");
            case "день", "деньнедели", "weekday" -> List.of("weekday");
            case "неделя", "типнедели", "weektype" -> List.of("weekType");
            case "время", "пара", "time" -> List.of("time");
            case "начало", "start", "starttime", "времяначала" -> List.of("start");
            case "конец", "end", "endtime", "времяокончания" -> List.of("end");
            case "тип", "вид", "lessontype" -> List.of("lessonType");
            case "подгруппа", "subgroup" -> List.of("subgroup");
            default -> List.of(key);
        };
    }

    private String canonical(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFKC)
                .replaceAll("[^a-zа-я0-9]", "");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record Header(int rowIndex, Map<String, Integer> columns) {
    }
}
