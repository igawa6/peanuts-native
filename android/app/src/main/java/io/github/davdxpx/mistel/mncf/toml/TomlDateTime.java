package io.github.davdxpx.mistel.mncf.toml;

import java.util.Objects;

/**
 * A TOML date-time value of one of the four kinds, with its fields and the text it was written as.
 * Fields that the kind does not have are 0: a local date has no time, a local time has no date,
 * and only an offset date-time has an offset.
 */
public final class TomlDateTime {

    /** The four kinds of date-time values in TOML. */
    public enum Kind {
        /** A date and a time with an offset from UTC, such as 1979-05-27T07:32:00Z. */
        OFFSET_DATE_TIME,
        /** A date and a time without an offset, such as 1979-05-27T07:32:00. */
        LOCAL_DATE_TIME,
        /** A date alone, such as 1979-05-27. */
        LOCAL_DATE,
        /** A time of day alone, such as 07:32:00. */
        LOCAL_TIME
    }

    private final Kind kind;
    private final String text;
    private final int year;
    private final int month;
    private final int day;
    private final int hour;
    private final int minute;
    private final int second;
    private final int nanosecond;
    private final int offsetMinutes;

    TomlDateTime(Kind kind, String text, int year, int month, int day, int hour, int minute,
            int second, int nanosecond, int offsetMinutes) {
        this.kind = kind;
        this.text = text;
        this.year = year;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.nanosecond = nanosecond;
        this.offsetMinutes = offsetMinutes;
    }

    /** Returns which of the four kinds this value is. */
    public Kind getKind() {
        return kind;
    }

    /** Returns the value exactly as the document wrote it. */
    public String getText() {
        return text;
    }

    /** Returns the year, 0 to 9999. */
    public int getYear() {
        return year;
    }

    /** Returns the month, 1 to 12. */
    public int getMonth() {
        return month;
    }

    /** Returns the day of the month, 1 to 31. */
    public int getDay() {
        return day;
    }

    /** Returns the hour, 0 to 23. */
    public int getHour() {
        return hour;
    }

    /** Returns the minute, 0 to 59. */
    public int getMinute() {
        return minute;
    }

    /** Returns the second, 0 to 60, where 60 is a leap second. */
    public int getSecond() {
        return second;
    }

    /** Returns the fraction of the second in nanoseconds. Digits after the ninth are dropped. */
    public int getNanosecond() {
        return nanosecond;
    }

    /** Returns the offset from UTC in minutes, negative west of UTC. */
    public int getOffsetMinutes() {
        return offsetMinutes;
    }

    /** Two date-times are equal when their kinds and fields are, however they were written. */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TomlDateTime)) {
            return false;
        }
        TomlDateTime that = (TomlDateTime) other;
        return kind == that.kind && year == that.year && month == that.month && day == that.day
                && hour == that.hour && minute == that.minute && second == that.second
                && nanosecond == that.nanosecond && offsetMinutes == that.offsetMinutes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, year, month, day, hour, minute, second, nanosecond, offsetMinutes);
    }

    /** Returns the value as the document wrote it. */
    @Override
    public String toString() {
        return text;
    }
}
