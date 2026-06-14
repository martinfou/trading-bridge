import java.time.Duration;
import java.time.Instant;

public class Test {
    public static void main(String[] args) {
        try {
            System.out.println("Starting test");
            long diff = Duration.between(Instant.MIN, Instant.now()).getSeconds();
            System.out.println("Diff: " + diff);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
