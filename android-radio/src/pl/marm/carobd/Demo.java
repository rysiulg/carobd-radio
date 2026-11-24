package pl.marm.carobd;

import org.json.*;
import java.util.*;

/** Przykład interfejsu w pamięci, nigdy w bazie ani na NAS. */
final class Demo {
    static List<JSONObject> rows() {
        try {
            ArrayList<JSONObject> items=new ArrayList<>();
            items.add(new JSONObject("{\"id\":\"demo-a\",\"t_start\":1795420200,\"km\":18.7,\"dur_s\":1428,\"l100\":7.1,\"accel_s\":[1.75,3.21,5.61,8.93,12.37,null,null]}"));
            items.add(new JSONObject("{\"id\":\"demo-b\",\"t_start\":1795402200,\"km\":7.2,\"dur_s\":674,\"l100\":6.4,\"accel_s\":[1.98,4.39,7.02,null,null,null,null]}"));
            return items;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    static List<Trips.Point> points(String id) {
        List<Trips.Point> p=new ArrayList<>();
        int[][] path={{5230800,2083500},{5230340,2084300},{5229880,2085230},{5229730,2086140},{5229620,2087300},
            {5229250,2087800},{5228580,2088170},{5228260,2088780},{5228080,2089810},{5227600,2090900},
            {5227460,2092290},{5227000,2092860},{5226570,2093260},{5226300,2093770},{5226060,2094520}};
        int n=id.endsWith("b") ? 9 : path.length;
        for (int i=0;i<n;i++) p.add(new Trips.Point(i*9,path[i][0],path[i][1],30+i*4));
        return p;
    }
}
