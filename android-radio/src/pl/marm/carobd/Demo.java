package pl.marm.carobd;

import org.json.*;
import java.util.*;

/** Przykład interfejsu w pamięci, nigdy w bazie ani na NAS. */
final class Demo {
    static List<JSONObject> rows() {
        try {
            ArrayList<JSONObject> items=new ArrayList<>();
            items.add(new JSONObject("{\"id\":\"demo-a\",\"t_start\":1795420200,\"t_end\":1795421628,\"km\":18.7,\"dur_s\":1428,\"l100\":7.1,\"fuel_l\":1.3277,\"cost_pln\":8.50,\"cost_net_pln\":6.91,\"fuel_vat_pct\":23,\"accel_s\":[1.75,3.21,5.61,8.93,12.37],\"accel_100_s\":[5.12,10.34,16.48,24.30,32.60]}"));
            items.add(new JSONObject("{\"id\":\"demo-b\",\"t_start\":1795419100,\"t_end\":1795419774,\"km\":7.2,\"dur_s\":674,\"l100\":6.4,\"fuel_l\":0.4608,\"cost_pln\":2.95,\"cost_net_pln\":2.40,\"fuel_vat_pct\":23,\"accel_s\":[1.98,4.39,7.02,null,null,null,null]}"));
            items.get(0).put(Trips.AMBIENT_C,0).put(Trips.PRESSURE_HPA,990);
            items.get(1).put(Trips.AMBIENT_C,2).put(Trips.PRESSURE_HPA,992);
            return items;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    static List<InstrumentHistory.Sample> instrumentSamples(InstrumentHistory.Metric metric,long now) {
        List<InstrumentHistory.Sample> values=new ArrayList<>();
        for (int d=6;d>=0;d--) for (int i=0;i<8;i++) {
            long at=now-d*24*3600_000L-(8-i)*InstrumentHistory.BUCKET;
            double value=metric==InstrumentHistory.Metric.VOLTAGE ? 12.1+i*.28+d*.07 :
                (metric==InstrumentHistory.Metric.COOLANT ? 54 : 61)+i*4+d;
            values.add(new InstrumentHistory.Sample(at,value,false));
        }
        return values;
    }
    static List<Trips.Point> points(String id) {
        List<Trips.Point> p=new ArrayList<>();
        int[][] path={{5230800,2083500},{5230340,2084300},{5229880,2085230},{5229730,2086140},{5229620,2087300},
            {5229250,2087800},{5228580,2088170},{5228260,2088780},{5228080,2089810},{5227600,2090900},
            {5227460,2092290},{5227000,2092860},{5226570,2093260},{5226300,2093770},{5226060,2094520}};
        boolean second=id.endsWith("b");
        int n=second ? 9 : path.length;
        for (int i=0;i<n;i++) p.add(new Trips.Point(i*9,path[i][0]+(second ? 1450-i*85 : 0),path[i][1],30+i*4,112+i*3+18*Math.sin(i*.72)));
        return p;
    }
}
