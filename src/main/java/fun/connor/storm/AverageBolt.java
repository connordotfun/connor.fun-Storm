package fun.connor.storm;

import java.util.List;
import java.util.Map;
import java.io.Serializable;
import java.lang.Math;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseWindowedBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.storm.windowing.TupleWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RegionData implements Serializable {
    private static final long serialVersionUID = 177788294969833253L;
    private final Logger LOG = LoggerFactory.getLogger(RegionData.class);    
    private List<String> tweetIDList;
    private List<Double> tweetSentimentList;
    private String avgTweetID;
    private Double avgTweetSent;
    private Double sumSentiment;
    private int counter;
    private Object regionJSON;
    private List<Boolean> tweetSensitvityList;
    private String regionID;
    
    public RegionData(Object regionJSON, String regionID){
        this.tweetIDList = new ArrayList<String>();
        this.tweetSentimentList = new ArrayList<Double>();
        this.tweetSensitvityList = new ArrayList<Boolean>();
        this.sumSentiment = 0.;
        this.counter = 0;
        this.regionJSON = regionJSON;
        this.regionID = regionID;
    }

    // Add a tweet to region data
    public void addTweet(String tweetID, Double tweetSentiment, Boolean sensitivity){
        //this.LOG.info("Adding tweet to region "+ this.regionID);
        
        this.tweetIDList.add(new String(tweetID));
        this.tweetSentimentList.add(new Double(tweetSentiment));
        this.tweetSensitvityList.add(new Boolean(sensitivity));
        this.sumSentiment += tweetSentiment;
        this.counter++;
    }
    
    public Double getAvgSent(){
        Double sent = this.sumSentiment/this.counter;
        Double sigm = 2*(1/(1+Math.exp(-sent))) - 1; // Mulitiply by 2, sub 1 to center on 0
        return sigm;
    }

    // Return arraylist of tweetIDs with sensitivity close to average
    public ArrayList<String> getAvgTweets(int numTweets){
        Double avgSent = this.getAvgSent();
        int size = this.tweetIDList.size();

        // Maps difference from tweet sentiment to average sentiment -> TweetID
        HashMap<Double,String> closestTweets = new HashMap<Double, String>();
        String tweetID;
        Double tweetSent;
        Boolean tweetSens;
        Double sentDiff;
        Double toRemove = null;
        Double maxDiff= -1.;
    
        for (int i = 0; i<size; i++){
            tweetID = this.tweetIDList.get(i);
            tweetSent = this.tweetSentimentList.get(i);
            tweetSens = this.tweetSensitvityList.get(i);
            sentDiff = Math.abs(tweetSent-avgSent);
            if (!tweetSens){
                if (closestTweets.size() < numTweets){
                    closestTweets.put(sentDiff, tweetID);
                }
                maxDiff = 0.;
                
                for (Double mapVal: closestTweets.keySet()){
                    // Find value that's most different and replace it within the map
                    if (sentDiff < mapVal && (mapVal-sentDiff)>maxDiff){
                        toRemove = mapVal;
                        maxDiff = mapVal-sentDiff;
                        //toRemove.add(mapVal);
                        //closestTweets.put(sentDiff, tweetID);
                    }
                }

                // To avoid concurrency error
                if (toRemove != null){
                    closestTweets.remove(toRemove);
                    closestTweets.put(sentDiff, tweetID);
                }
            }
        }
        
        this.LOG.info("Returning ArrayList of size "+closestTweets.size());
        return new ArrayList<String>(closestTweets.values());
    }

    public void emitValues(OutputCollector collector, int numTweets){
        Double avgSentiment = this.getAvgSent();
        ArrayList<String> avgTweetIDs = this.getAvgTweets(numTweets);
        // For now: Keep emitting one value
        if (avgTweetIDs.size() > 0){
            collector.emit(new Values(this.regionID, avgSentiment, avgTweetIDs, this.regionJSON, this.counter));   
        }
        else{
            this.LOG.info("ERROR: No unsensitive tweets found! Tweet IDS: "+this.tweetIDList + 
                ", Sensitivity: "+this.tweetSensitvityList);
        }     
    }
}


public class AverageBolt extends BaseWindowedBolt {
    private static final long serialVersionUID = 177788294989633253L;
    private final Logger LOG = LoggerFactory.getLogger(AverageBolt.class);
    private OutputCollector collector;

    @Override
    public void prepare(Map topoConf, TopologyContext context, OutputCollector collector) {
        this.collector = collector;
    }

    @Override
    public void execute(TupleWindow inputWindow) {
        // Map the regionID to data
        HashMap<String, RegionData> map = new HashMap<String, RegionData>();

        List<Tuple> tuplesInWindow = inputWindow.get();
        if (tuplesInWindow.size() > 0) {
            RegionData structure;

            for (Tuple tuple : tuplesInWindow) {
                String regionID = new String(tuple.getString(0));
                if (map.containsKey(regionID)){
                    structure = map.get(regionID);
                    structure.addTweet(tuple.getString(2), tuple.getDouble(1), tuple.getBoolean(4));
                    map.replace(regionID, structure);
                }
                else{
                    structure = new RegionData(tuple.getValue(3), regionID);
                    structure.addTweet(tuple.getString(2), tuple.getDouble(1), tuple.getBoolean(4));
                    map.put(regionID, structure);      
                    LOG.info("Created new structure with regionID: "+regionID);
                }
            }

            // Emit each regionID
            for (String mapKey: map.keySet()){
                structure = map.get(mapKey);
                // Change number to change number of tweets emitted.
                structure.emitValues(collector, 5);
            }

        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("regionID", "avgSentiment", "exemplarTweetID", "regionJSON","tweetCount"));
    }

}
