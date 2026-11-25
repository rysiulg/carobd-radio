package pl.marm.carobd;

/** TCM Sorento, pola 20 i 21 w danych odpowiedzi 2201A0 / 21A0. */
final class KiaGear {
    static final String UDS="2201A0", LEGACY="21A0";
    private static final long POLL_MS=1100, QUIET_MS=200, RETRY_MS=2400, UNSUPPORTED_MS=25_000;
    private static final int SELECTOR=20, CURRENT=21;
    final int selector, current;

    private KiaGear(int selector,int current) { this.selector=selector; this.current=current; }

    static KiaGear decode(String command,String reply) {
        String prefix=UDS.equals(command) ? "6201A0" : "61A0";
        String data=EngineHours.payload(reply,prefix,prefix.length()/2+CURRENT+1,80);
        if (data==null) return null;
        return new KiaGear(byteAt(data,SELECTOR),byteAt(data,CURRENT));
    }
    private static int byteAt(String data,int i) { return Integer.parseInt(data.substring(i*2,i*2+2),16); }
    static long nextPoll(long started,long finished,boolean success,boolean supported) {
        if (success) return Math.max(started+POLL_MS,finished+QUIET_MS);
        return finished+(supported ? RETRY_MS : UNSUPPORTED_MS);
    }

    String label(double speed) {
        switch (selector) {
            case 0: case 11: return speed>5 ? "" : "P";
            case 7: case 10: return "R";
            case 6: case 9: return "N";
            case 5: case 8: return forward("D");
            case 12: return forward("M");
            default: return current>=1 && current<=8 ? Integer.toString(current) : "";
        }
    }
    private String forward(String mode) { return current>=1 && current<=8 ? mode+current : mode; }
}
