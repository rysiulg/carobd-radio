package pl.marm.carobd;

import java.util.Arrays;

public final class RoadAlertTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        double lat=51, lon=20;
        RoadAlert.Site camera=new RoadAlert.Site("camera","DK1",lat+.003,lon,Double.NaN,Double.NaN);
        RoadAlert.Site section=new RoadAlert.Site("opp","S7",lat+.004,lon,lat+.025,lon);
        RoadAlert.Site sideRoad=new RoadAlert.Site("east","",lat+.002,lon+.004,Double.NaN,Double.NaN);
        RoadAlert alerts=new RoadAlert();
        check(alerts.observe(Arrays.asList(camera,section,sideRoad),lat,lon,180,90,1000)==null);
        check(alerts.observe(Arrays.asList(camera),lat,lon,0,0,1000)==null);
        RoadAlert.Approach first=alerts.observe(Arrays.asList(camera,section,sideRoad),lat,lon,0,90,1000);
        check(first!=null && first.site==camera && first.meters==330);
        RoadAlert.Approach second=alerts.observe(Arrays.asList(camera,section,sideRoad),lat,lon,0,90,2000);
        check(second!=null && second.site==section);
        check(alerts.observe(Arrays.asList(camera,section,sideRoad),lat,lon,0,90,3000)==null);
        check(new RoadAlert().observe(Arrays.asList(section),lat+.007,lon,180,90,4000)==null);
        check(alerts.observe(Arrays.asList(camera),lat,lon,0,90,301_000)!=null);
        System.out.println("OK: kierunek, odległość, początek OPP i ograniczenie powtórzeń");
    }
}
