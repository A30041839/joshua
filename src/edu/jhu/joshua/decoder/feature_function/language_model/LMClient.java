package edu.jhu.joshua.decoder.feature_function.language_model;


import java.util.ArrayList;


public abstract class LMClient {
	
    public LMClient(){
    }
	
    public LMClient(String hostname, int port){
    	
    }
    
    //TODO
    public void close_client(){
    	
    }
    
    //cmd: prob order wrd1 wrd2 ...
    public abstract double get_prob(ArrayList ngram, int order);
    
    //cmd: prob order wrd1 wrd2 ...
    public abstract double get_prob(int[] ngram, int order);
    
    //cmd: prob order wrd1 wrd2 ...
    public abstract double get_prob_backoff_state(int[] ngram, int n_additional_bow);
   
    public abstract int[] get_left_euqi_state(int[] original_state_wrds, int order, double[] cost);
   
    public abstract int[] get_right_euqi_state(int[] original_state, int order);
    
       
  
}

