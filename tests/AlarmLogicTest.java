import com.bvalarm.app.Alarm;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;

/** Desktop JVM checks for the pure scheduling math in Alarm. */
public class AlarmLogicTest {

    private static int failures = 0;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static void check(String name, long actual, long expected) {
        if (actual != expected) {
            failures++;
            System.out.println("FAIL " + name + "\n  expected " + expected + "\n  actual   " + actual);
        } else {
            System.out.println("ok   " + name);
        }
    }

    private static long at(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZONE).toInstant().toEpochMilli();
    }

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        long friday1747 = at(2026, 11, 6, 17, 47);

        Alarm a = new Alarm();
        a.hour = 7;
        a.minute = 30;
        a.days = 0;
        check("one-shot, time passed today -> tomorrow", a.nextTrigger(friday1747), at(2026, 11, 7, 7, 30));

        a.hour = 18;
        a.minute = 0;
        check("one-shot, later today", a.nextTrigger(friday1747), at(2026, 11, 6, 18, 0));

        a.hour = 7;
        a.minute = 30;
        a.days = 0x1F;
        check("workdays, friday evening -> monday", a.nextTrigger(friday1747), at(2026, 11, 9, 7, 30));

        a.days = 0x60;
        check("weekend -> saturday", a.nextTrigger(friday1747), at(2026, 11, 7, 7, 30));

        a.days = 0x7F;
        check("every day, exactly at time -> next day",
                a.nextTrigger(at(2026, 11, 6, 7, 30)), at(2026, 11, 7, 7, 30));

        a.days = 0x01; // monday only
        check("monday only, monday early morning -> same day",
                a.nextTrigger(at(2026, 11, 9, 6, 0)), at(2026, 11, 9, 7, 30));

        check("monday only, monday after alarm -> next monday",
                a.nextTrigger(at(2026, 11, 9, 8, 0)), at(2026, 11, 16, 7, 30));

        a.days = 0;
        a.hour = 23;
        a.minute = 59;
        check("one-shot late night", a.nextTrigger(at(2026, 11, 6, 23, 59)), at(2026, 11, 7, 23, 59));

        Alarm b = new Alarm();
        b.days = 0x1F;
        System.out.println((b.daysSummary().equals("工作日") ? "ok   " : "FAIL ") + "summary workdays: " + b.daysSummary());
        b.days = 0x60;
        System.out.println((b.daysSummary().equals("周末") ? "ok   " : "FAIL ") + "summary weekend: " + b.daysSummary());
        b.days = 0x7F;
        System.out.println((b.daysSummary().equals("每天") ? "ok   " : "FAIL ") + "summary everyday: " + b.daysSummary());
        b.days = 0;
        System.out.println((b.daysSummary().equals("仅一次") ? "ok   " : "FAIL ") + "summary once: " + b.daysSummary());

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILURES");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
