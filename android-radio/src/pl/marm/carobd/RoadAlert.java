package pl.marm.carobd;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Wybiera najbliższy pomiar przed autem na podstawie kursu i pozycji GPS. */
final class RoadAlert {
    private static final double METERS_PER_DEGREE=111195, MIN_SPEED=12;
    private static final long COOLDOWN_MS=5*60_000L;
    private final Map<String,Long> warned=new HashMap<>();

    static final class Site {
        final String id, location;
        final double lat, lon, endLat, endLon, heading;
        Site(String id,String location,double lat,double lon,double endLat,double endLon) {
            this.id=id; this.location=location; this.lat=lat; this.lon=lon; this.endLat=endLat; this.endLon=endLon;
            this.heading=section() ? bearing(lat,lon,endLat,endLon) : Double.NaN;
        }
        boolean section() { return Double.isFinite(endLat) && Double.isFinite(endLon); }
    }
    static final class Approach {
        final Site site;
        final int meters;
        Approach(Site site,int meters) { this.site=site; this.meters=meters; }
        String title() { return site.section() ? "Początek pomiaru odcinkowego" : "Fotoradar"; }
        String detail() { return "Za "+meters+" m"+(site.location.isEmpty() ? "" : " · "+site.location); }
    }

    Approach observe(List<Site> sites,double lat,double lon,double heading,double kmh,long now) {
        if (!Double.isFinite(heading) || !Double.isFinite(kmh) || kmh<MIN_SPEED) return null;
        double ahead=Math.min(650,Math.max(220,kmh*5)), best=ahead;
        Site nearest=null;
        double metersPerLon=METERS_PER_DEGREE*Math.cos(Math.toRadians(lat));
        double course=Math.toRadians(heading), northUnit=Math.cos(course), eastUnit=Math.sin(course);
        for (Site site:sites) {
            double north=(site.lat-lat)*METERS_PER_DEGREE, east=(site.lon-lon)*metersPerLon;
            if (Math.abs(north)>best || Math.abs(east)>best) continue;
            double along=north*northUnit+east*eastUnit, across=Math.abs(east*northUnit-north*eastUnit);
            if (along<25 || across>Math.min(110,along*.55)) continue;
            if (Double.isFinite(site.heading) && difference(heading,site.heading)>55) continue;
            Long last=warned.get(site.id);
            if (last!=null && now-last<COOLDOWN_MS) continue;
            double range=Math.hypot(north,east);
            if (range<=best) { best=range; nearest=site; }
        }
        if (nearest==null) return null;
        warned.put(nearest.id,now);
        return new Approach(nearest,Math.max(10,(int)Math.round(best/10)*10));
    }
    static double bearing(double lat,double lon,double toLat,double toLon) {
        double east=(toLon-lon)*Math.cos(Math.toRadians((lat+toLat)/2));
        return (360+Math.toDegrees(Math.atan2(east,toLat-lat)))%360;
    }
    static double distance(double lat,double lon,double toLat,double toLon) {
        double east=(toLon-lon)*Math.cos(Math.toRadians((lat+toLat)/2));
        return Math.hypot(toLat-lat,east)*METERS_PER_DEGREE;
    }
    private static double difference(double first,double second) {
        return Math.abs((first-second+540)%360-180);
    }
}
