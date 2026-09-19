package dev.monocle.coordinator;

import com.google.gson.*;

/** Advisory visible-road timing, never a work/admission gate. */
public final class RoadForecast {
    private RoadForecast() {}
    public static double breakTicks(double delta) { return delta > 0 ? Math.ceil(1 / delta) : 1_000_000_000; }
    public static double miningTicks(double delta, boolean doubleMiningObsidian, int delay) {
        return breakTicks(delta) * (doubleMiningObsidian ? 0.55 : 1) + delay;
    }
    public static double rate(int rows, double miningTicks, int placements, double tps, double movement, int placesPerTick) {
        if(rows<=0||miningTicks<0||placements<0||!Double.isFinite(miningTicks)||!Double.isFinite(tps)||!Double.isFinite(movement)||tps<=0||movement<=0||placesPerTick<=0)return 0;
        double seconds=Math.max(rows/movement,placements/(Math.min(20,tps)*placesPerTick))+miningTicks/Math.min(20,tps);
        return rows/seconds;
    }
    public static JsonObject checked(JsonObject forecast) {
        int rows=ResourceLedger.integer(forecast,"nextBlocks"),age=ResourceLedger.integer(forecast,"ageTicks");
        if(!forecast.getAsJsonPrimitive("blocksPerSecond").isNumber())throw new IllegalArgumentException("Invalid road forecast");double rate=forecast.get("blocksPerSecond").getAsDouble();
        if(rows<0||rows>1024||age<0||age>200||!Double.isFinite(rate)||rate<0||rate>1000)throw new IllegalArgumentException("Invalid road forecast");
        JsonObject result=new JsonObject();result.addProperty("nextBlocks",rows);result.addProperty("blocksPerSecond",rate);
        result.addProperty("ageTicks",age);return result;
    }
    /** Conservative bottleneck approximation; never extrapolates beyond the common observed horizon. */
    public static JsonObject crew(JsonArray workers) {
        int rows=1024,count=0,age=0;double rate=1000;
        for(var value:workers) {JsonObject worker=value.getAsJsonObject();if(!worker.has("roadPrediction"))continue;
            try {JsonObject f=checked(worker.getAsJsonObject("roadPrediction"));rows=Math.min(rows,f.get("nextBlocks").getAsInt());rate=Math.min(rate,f.get("blocksPerSecond").getAsDouble());age=Math.max(age,f.get("ageTicks").getAsInt());count++;}catch(RuntimeException ignored) {} }
        if(count==0)return null;JsonObject result=new JsonObject();result.addProperty("nextBlocks",rows);result.addProperty("blocksPerSecond",rate);result.addProperty("ageTicks",age);result.addProperty("workers",count);return result;
    }
}
