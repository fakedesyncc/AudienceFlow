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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final Pattern ACADEMIC_YEAR_PATTERN = Pattern.compile("(20\\d{2})\\s*[-/]\\s*(20\\d{2})");
    private static final Pattern TEACHER_MARKER_PATTERN = Pattern.compile(
            "(?iu)(проф\\.|доц\\.|ст\\.\\s*пр\\.|асс\\.|преп\\.|пр\\.)\\s+(.+)$"
    );
    private static final List<RoomRange> ROOM_RANGES = List.of(
            new RoomRange(100, 111, "Корпус 1"),
            new RoomRange(204, 239, "Корпус 1"),
            new RoomRange(309, 346, "Корпус 1"),
            new RoomRange(408, 440, "Корпус 1"),
            new RoomRange(112, 115, "Корпус 2"),
            new RoomRange(240, 263, "Корпус 2"),
            new RoomRange(347, 382, "Корпус 2"),
            new RoomRange(441, 478, "Корпус 2"),
            new RoomRange(117, 121, "Корпус 3"),
            new RoomRange(264, 272, "Корпус 3"),
            new RoomRange(122, 130, "Корпус 4"),
            new RoomRange(273, 282, "Корпус 4"),
            new RoomRange(131, 146, "Корпус 5"),
            new RoomRange(283, 299, "Корпус 5"),
            new RoomRange(383, 399, "Корпус 5"),
            new RoomRange(479, 499, "Корпус 5"),
            new RoomRange(504, 514, "Корпус 5")
    );

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
            List<ScheduleRepository.ImportedScheduleRow> rows = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                Header header = findHeader(sheet);
                if (header != null) {
                    parseNormalizedSheet(sheet, header, rows, warnings);
                    continue;
                }

                WideHeader wideHeader = findWideHeader(sheet);
                if (wideHeader != null) {
                    parseWideSheet(sheet, wideHeader, rows, warnings);
                }
            }

            if (rows.isEmpty() && warnings.isEmpty()) {
                warnings.add("Не найдена строка заголовков. Поддерживаются нормализованные таблицы и формат расписания ЛГТУ с колонками групп.");
            }
            int imported = scheduleRepository.importRows(rows, blankTo(source, "excel"));
            return new ScheduleImportResult(rows.size(), imported, warnings.size(), warnings.stream().limit(20).toList());
        }
    }

    private void parseNormalizedSheet(
            Sheet sheet,
            Header header,
            List<ScheduleRepository.ImportedScheduleRow> rows,
            List<String> warnings
    ) {
        for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isBlank(row)) {
                continue;
            }
            try {
                rows.add(parseRow(row, header.columns()));
            } catch (IllegalArgumentException exception) {
                warnings.add(sheet.getSheetName() + ", строка " + (rowIndex + 1) + ": " + exception.getMessage());
            }
        }
    }

    private void parseWideSheet(
            Sheet sheet,
            WideHeader header,
            List<ScheduleRepository.ImportedScheduleRow> rows,
            List<String> warnings
    ) {
        SchedulePeriod period = detectPeriod(sheet);
        String currentWeekday = "";
        String currentTime = "";

        for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isBlank(row)) {
                continue;
            }

            String weekdayCell = cellText(row.getCell(header.weekdayColumn())).trim();
            String timeCell = cellText(row.getCell(header.timeColumn())).trim();
            if (!weekdayCell.isBlank()) {
                currentWeekday = weekdayCell;
            }
            if (!timeCell.isBlank()) {
                currentTime = timeCell;
            }
            if (currentWeekday.isBlank() || currentTime.isBlank()) {
                continue;
            }

            for (WideGroupColumn groupColumn : header.groups()) {
                String lessonText = cellText(row.getCell(groupColumn.groupColumn())).trim();
                String roomText = cellText(row.getCell(groupColumn.roomColumn())).trim();
                if (lessonText.isBlank() || roomText.isBlank()) {
                    continue;
                }
                try {
                    rows.add(parseWideRow(groupColumn.groupName(), currentWeekday, currentTime, lessonText, roomText, period));
                } catch (IllegalArgumentException exception) {
                    warnings.add(sheet.getSheetName() + ", строка " + (rowIndex + 1) + ", "
                            + groupColumn.groupName() + ": " + exception.getMessage());
                }
            }
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

    private WideHeader findWideHeader(Sheet sheet) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), 40); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }

            Integer weekdayColumn = null;
            Integer timeColumn = null;
            List<Integer> groupColumns = new ArrayList<>();
            List<Integer> roomColumns = new ArrayList<>();
            for (Cell cell : row) {
                String text = cellText(cell);
                String key = canonical(text);
                if (key.equals("деньнедели") || key.equals("день")) {
                    weekdayColumn = cell.getColumnIndex();
                } else if (key.contains("времяначала") || key.equals("время") || key.equals("пара")) {
                    timeColumn = cell.getColumnIndex();
                } else if (key.startsWith("группа")) {
                    groupColumns.add(cell.getColumnIndex());
                } else if (key.equals("аудитория") || key.equals("аудиторрия") || key.equals("ауд")) {
                    roomColumns.add(cell.getColumnIndex());
                }
            }

            if (weekdayColumn == null || timeColumn == null || groupColumns.isEmpty() || roomColumns.isEmpty()) {
                continue;
            }

            roomColumns.sort(Comparator.naturalOrder());
            List<WideGroupColumn> groups = new ArrayList<>();
            for (Integer groupColumn : groupColumns) {
                String groupName = cleanGroupName(cellText(row.getCell(groupColumn)));
                Integer nextGroupColumn = groupColumns.stream()
                        .filter(candidate -> candidate > groupColumn)
                        .min(Integer::compareTo)
                        .orElse(Integer.MAX_VALUE);
                Integer roomColumn = roomColumns.stream()
                        .filter(candidate -> candidate > groupColumn && candidate < nextGroupColumn)
                        .findFirst()
                        .orElse(null);
                if (!groupName.isBlank() && roomColumn != null) {
                    groups.add(new WideGroupColumn(groupName, groupColumn, roomColumn));
                }
            }
            if (!groups.isEmpty()) {
                return new WideHeader(rowIndex, weekdayColumn, timeColumn, groups);
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

    private ScheduleRepository.ImportedScheduleRow parseWideRow(
            String groupName,
            String weekday,
            String timeRange,
            String lessonText,
            String roomText,
            SchedulePeriod period
    ) {
        LessonParts lesson = parseLesson(lessonText);
        RoomParts room = parseRoom(roomText);
        LocalTime[] range = parseTimeRange(timeRange);
        return new ScheduleRepository.ImportedScheduleRow(
                groupName,
                "Институт компьютерных наук",
                lesson.teacherName(),
                "",
                lesson.disciplineName(),
                room.roomName(),
                room.building(),
                room.floor(),
                room.capacity(),
                null,
                parseWeekday(weekday),
                "any",
                range[0],
                range[1],
                room.lessonType(),
                "",
                period.validFrom(),
                period.validTo()
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

    private LocalTime[] parseTimeRange(String value) {
        String range = value.replace('–', '-').replace('—', '-');
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

    private LessonParts parseLesson(String value) {
        String normalized = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("пустое занятие");
        }

        Matcher matcher = TEACHER_MARKER_PATTERN.matcher(normalized);
        if (matcher.find() && matcher.start() > 0) {
            String discipline = normalized.substring(0, matcher.start()).trim();
            String teacher = matcher.group(2).trim();
            return new LessonParts(blankTo(discipline, normalized), blankTo(teacher, "Преподаватель не указан"));
        }
        return new LessonParts(normalized, "Преподаватель не указан");
    }

    private RoomParts parseRoom(String value) {
        String normalized = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("пустая аудитория");
        }

        String lessonType = detectLessonType(normalized);
        String roomName = normalized
                .replaceAll("(?iu)(^|\\s)(лек|лаб|пр)\\.?(?=\\s|$)", " ")
                .replaceAll("\\s+", " ")
                .trim();
        roomName = roomName.isBlank() ? normalized : roomName;
        roomName = normalizeRoomName(roomName);
        String building = inferBuilding(roomName);
        String floor = inferFloor(roomName);
        int capacity = switch (lessonType) {
            case "лекция" -> 120;
            case "лабораторная" -> 24;
            default -> roomName.toLowerCase(Locale.ROOT).contains("спорт") ? 80 : 30;
        };
        return new RoomParts(roomName, building, floor, capacity, lessonType);
    }

    private String detectLessonType(String value) {
        String normalized = canonical(value);
        if (normalized.contains("лек")) {
            return "лекция";
        }
        if (normalized.contains("лаб")) {
            return "лабораторная";
        }
        if (normalized.contains("пр")) {
            return "практика";
        }
        return "занятие";
    }

    private String normalizeRoomName(String value) {
        String trimmed = value.replace('–', '-').replace('—', '-').trim();
        if (canonical(trimmed).contains("спортзал")) {
            return "Спортзал";
        }
        Matcher lecture = Pattern.compile("(?iu)^л\\s*-?\\s*(\\d+)$").matcher(trimmed);
        if (lecture.find()) {
            return "Л-" + lecture.group(1);
        }
        Matcher prefixedNine = Pattern.compile("^9\\s*-\\s*(\\d{3})$").matcher(trimmed);
        if (prefixedNine.find()) {
            return "9-" + prefixedNine.group(1);
        }
        return trimmed;
    }

    private String inferBuilding(String roomName) {
        String lower = roomName.toLowerCase(Locale.ROOT);
        if (lower.contains("спорт")) {
            return "Спортивный комплекс";
        }
        if (roomName.matches("(?iu)^л-\\d+$")) {
            return "Аудиторный корпус";
        }
        if (roomName.matches("^9[-\\s]?\\d{3}$")) {
            return "Корпус 9";
        }
        if (roomName.matches("(?iu)^б[-\\s]?\\d+.*$")) {
            return "Корпус Б";
        }
        if (roomName.matches("(?iu)^с[-\\s]?\\d+.*$")) {
            return "Корпус С";
        }
        Integer numericRoom = parseLeadingNumber(roomName);
        if (numericRoom != null) {
            return ROOM_RANGES.stream()
                    .filter(range -> range.includes(numericRoom))
                    .findFirst()
                    .map(RoomRange::building)
                    .orElse("Корпус");
        }
        return "Корпус";
    }

    private String inferFloor(String roomName) {
        if (roomName.matches("(?iu)^л-\\d+$")) {
            return "Л";
        }
        if (roomName.matches("^9[-\\s]?(\\d{3})$")) {
            return roomName.replaceAll("\\D", "").substring(1, 2);
        }
        Integer numericRoom = parseLeadingNumber(roomName);
        if (numericRoom != null && numericRoom >= 100) {
            return String.valueOf(String.valueOf(numericRoom).charAt(0));
        }
        return "1";
    }

    private Integer parseLeadingNumber(String value) {
        Matcher matcher = Pattern.compile("(\\d{3})").matcher(value);
        if (!matcher.find()) {
            return null;
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String cleanGroupName(String value) {
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("(?iu)^\\s*группа\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private SchedulePeriod detectPeriod(Sheet sheet) {
        StringBuilder text = new StringBuilder();
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= Math.min(sheet.getLastRowNum(), 5); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                String value = cellText(cell);
                if (!value.isBlank()) {
                    text.append(' ').append(value);
                }
            }
        }
        String normalized = canonical(text.toString());
        Matcher yearMatcher = ACADEMIC_YEAR_PATTERN.matcher(text);
        if (yearMatcher.find()) {
            int startYear = Integer.parseInt(yearMatcher.group(1));
            int endYear = Integer.parseInt(yearMatcher.group(2));
            if (normalized.contains("весенний")) {
                return new SchedulePeriod(LocalDate.of(endYear, 2, 1), LocalDate.of(endYear, 8, 31));
            }
            if (normalized.contains("осенний")) {
                return new SchedulePeriod(LocalDate.of(startYear, 9, 1), LocalDate.of(endYear, 1, 31));
            }
        }
        return new SchedulePeriod(LocalDate.now().minusMonths(1), null);
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

    private record WideHeader(int rowIndex, int weekdayColumn, int timeColumn, List<WideGroupColumn> groups) {
    }

    private record WideGroupColumn(String groupName, int groupColumn, int roomColumn) {
    }

    private record LessonParts(String disciplineName, String teacherName) {
    }

    private record RoomParts(String roomName, String building, String floor, int capacity, String lessonType) {
    }

    private record SchedulePeriod(LocalDate validFrom, LocalDate validTo) {
    }

    private record RoomRange(int start, int end, String building) {
        private boolean includes(int room) {
            return room >= start && room <= end;
        }
    }
}
