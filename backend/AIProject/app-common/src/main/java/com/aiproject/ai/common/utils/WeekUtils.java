package com.aiproject.ai.common.utils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class WeekUtils {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * 获取本周范围（周一 ~ 周日）
     */
    public static Map<String, String> getCurrentWeekRange() {
        LocalDate start = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate end = LocalDate.now().with(DayOfWeek.SUNDAY);
        
        Map<String, String> range = new HashMap<>();
        range.put("startDate", start.format(DATE_FORMATTER));
        range.put("endDate", end.format(DATE_FORMATTER));
        range.put("startDateTime", start.atStartOfDay().format(DATE_TIME_FORMATTER));
        range.put("endDateTime", end.atTime(LocalTime.MAX).format(DATE_TIME_FORMATTER));
        
        return range;
    }
    
    /**
     * 获取上周范围
     */
    public static Map<String, String> getLastWeekRange() {
        LocalDate start = LocalDate.now().minusWeeks(1).with(DayOfWeek.MONDAY);
        LocalDate end = LocalDate.now().minusWeeks(1).with(DayOfWeek.SUNDAY);
        
        Map<String, String> range = new HashMap<>();
        range.put("startDate", start.format(DATE_FORMATTER));
        range.put("endDate", end.format(DATE_FORMATTER));
        
        return range;
    }
    
    /**
     * 获取下周范围
     */
    public static Map<String, String> getNextWeekRange() {
        LocalDate start = LocalDate.now().plusWeeks(1).with(DayOfWeek.MONDAY);
        LocalDate end = LocalDate.now().plusWeeks(1).with(DayOfWeek.SUNDAY);
        
        Map<String, String> range = new HashMap<>();
        range.put("startDate", start.format(DATE_FORMATTER));
        range.put("endDate", end.format(DATE_FORMATTER));
        
        return range;
    }
    
    /**
     * 判断某个日期是否在本周内
     */
    public static boolean isInCurrentWeek(LocalDate date) {
        LocalDate weekStart = LocalDate.now().with(DayOfWeek.MONDAY);
        LocalDate weekEnd = LocalDate.now().with(DayOfWeek.SUNDAY);
        return !date.isBefore(weekStart) && !date.isAfter(weekEnd);
    }
    
    public static void main(String[] args) {
        System.out.println("本周范围: " + getCurrentWeekRange());
        System.out.println("上周范围: " + getLastWeekRange());
        System.out.println("下周范围: " + getNextWeekRange());
        
        LocalDate today = LocalDate.now();
        System.out.println("今天是否在本周: " + isInCurrentWeek(today));
    }
}