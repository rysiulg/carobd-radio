package pl.marm.carobd;

public final class ObdRetryTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        ObdRetry retry=new ObdRetry();
        check(retry.failed()==2000);
        check(retry.failed()==4000);
        check(retry.failed()==8000);
        check(retry.failed()==15000);
        for (int i=0;i<20;i++) check(retry.failed()==30000);
        check(retry.possibleBusy(true) && !retry.possibleBusy(false));
        retry.reset();
        check(!retry.possibleBusy(true) && retry.failed()==2000);
        check(!retry.possibleBusy(true));
        retry.failed();
        check(retry.possibleBusy(true));
        System.out.println("OK: szybkie ponowienie, limit, osobna obserwacja BLE i reset po odzyskaniu łącza");
    }
}
