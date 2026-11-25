package pl.marm.carobd;

/** Przedziały co 10 km/h. Te same kolory są w route-speed.js na NAS. */
final class SpeedBands {
    static final int STEP=10;
    static final int[] COLORS={
        0xff98a8b8,0xff285de0,0xff6bb9ff,0xff7037cf,0xff21dce9,0xff007f70,0xff80ec49,0xff21853e,
        0xffd8f154,0xff947100,0xffffd841,0xffbd5100,0xffffa554,0xffb62531,0xffff6571,0xffa82383,
        0xffff96cb,0xff5e408f,0xffbf9ffe,0xff4043a9,0xffcbd0ff,0xff006090,0xff91e5ff,0xff00604e,
        0xffb4f6d6,0xff436719,0xffd4ef86,0xff6b5009,0xffffe793,0xff923b1d,0xffffbf98,0xff7d2542,
        0xffffa8b8,0xff722267,0xfff4aacc,0xff514178,0xffe2caff,0xff38377b,0xffb7bcfb,0xff3a5772,0xffdce8ff
    };
    static final int UNKNOWN=COLORS.length, NO_SPEED=0xffa6b6c5;
    static int band(int first,int second) {
        if (first<0 && second<0) return UNKNOWN;
        int speed=first<0 ? second : second<0 ? first : (first+second)/2;
        return Math.min(UNKNOWN-1,speed/STEP);
    }
    private SpeedBands() {}
}
