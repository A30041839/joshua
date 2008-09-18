/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or 
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package edu.jhu.joshua.decoder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.jhu.joshua.decoder.chart_parser.Chart;
import edu.jhu.joshua.decoder.feature_function.FeatureFunction;
import edu.jhu.joshua.decoder.feature_function.ArityPhrasePenaltyFF;
import edu.jhu.joshua.decoder.feature_function.PhraseModelFF;
import edu.jhu.joshua.decoder.feature_function.WordPenaltyFF;
import edu.jhu.joshua.decoder.feature_function.language_model.LMGrammar;
import edu.jhu.joshua.decoder.feature_function.language_model.LMGrammar_JAVA;
import edu.jhu.joshua.decoder.feature_function.language_model.LMGrammar_REMOTE;
import edu.jhu.joshua.decoder.feature_function.language_model.LMModel;
import edu.jhu.joshua.decoder.feature_function.language_model.srilm.LMGrammar_SRILM;
import edu.jhu.joshua.decoder.feature_function.translation_model.TMGrammar;
import edu.jhu.joshua.decoder.feature_function.translation_model.TMGrammar_Memory;
import edu.jhu.joshua.decoder.hypergraph.DiskHyperGraph;
import edu.jhu.joshua.decoder.hypergraph.HyperGraph;
import edu.jhu.joshua.decoder.hypergraph.KbestExtraction;
import edu.jhu.joshua.lattice.Lattice;
import edu.jhu.lzfUtility.FileUtility;

/**
 * this class implements: 
 * (1) read config file, initialize, call the chart-parsing functions 
 * (2) parallel decoding
 * 
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate$
 */
public class Decoder {
	//input files
	public static String lm_file;
	public static String tm_file;

	//lm config
	public static boolean use_srilm=false;
	public static double lm_ceiling_cost=100;
	public static boolean use_left_euqivalent_state=false;
	public static boolean use_right_euqivalent_state=true;
	public static int g_lm_order=3;
	public static boolean use_sent_specific_lm=false;
	public static String g_sent_lm_file_name_prefix="lm.";
	
	//tm config
	public static int span_limit =10;
	//note: owner should be different from each other, it can have same value as a word in LM/TM
	public static String phrase_owner="pt";
	public static String mono_owner="mono";
	public static String begin_mono_owner="begin_mono";//if such a rule is get applied, then no reordering is possible
	public static String default_non_terminal="PHRASE";
	public static boolean use_sent_specific_tm=false;
	public static String g_sent_tm_file_name_prefix="tm.";
	
	//pruning config
	public static boolean use_cube_prune=true;
	public static double fuzz1=0.1;
	public static double fuzz2=0.1;
	public static int max_n_items=30;
	public static double relative_threshold=10.0;
	public static int max_n_rules=50;
	public static double rule_relative_threshold=10.0;
	
	//nbest config
	public static boolean use_unique_nbest =false;
	public static boolean use_tree_nbest =false;
	public static boolean add_combined_cost =true;//in the nbest file, compute the final socre
	public static int topN=500;
	
	//remote lm server
	public static boolean use_remote_lm_server=false;
	public static String remote_symbol_tbl = "null"; //this file will first be created by remote_lm_server, and read by remote_suffix_server and the decoder	
	public static int num_remote_lm_servers =1;
	public static String f_remote_server_list="null";
	
	//parallel decoding
	public static String parallel_files_prefix="/tmp/temp.parallel"; // C:\\Users\\zli\\Documents\\temp.parallel; used for parallel decoding
	public static int num_parallel_decoders=1; //number of threads should run
	public static Thread[] l_parallel_decode_threads;
	public static String[] l_parallel_test_files;
	public static String[] l_parallel_nbest_files;
	public static DiskHyperGraph[] l_parallel_disk_hgs;
	public static KbestExtraction[] l_parallel_kbest_extractors;
	
	//### global variables
	public static LMGrammar p_lm=null;//the lm itself
	public static LMModel p_lm_model=null;//general model
	public static TMGrammar[] p_tm_grammars=null;
	
	public static  ArrayList<FeatureFunction> p_l_models=null;
	public static ArrayList<Integer> l_default_nonterminals=null; 
	
	//disk
	public static boolean save_disk_hg = false;//if true, save three files: fnbest, fnbest.hg.items, fnbest.hg.rules
	public static boolean forest_pruning =false;
	public static double forest_pruning_threshold=10;
	
	
	//debug
	public static boolean extract_confusion_grammar = false; //non-parallel version
	public static String f_confusion_grammar="C:\\Users\\zli\\Documents\\confusion.hg.grammar";
	
	private static final Logger logger = Logger.getLogger(Decoder.class.getName());
	
	public static void main(String[] args) {	
		
		if (logger.isLoggable(Level.FINEST)) logger.finest("Starting decoder");
		
		long start= System.currentTimeMillis();
		if(args.length!=3){
			System.out.println("wrong command, correct command should be: java Decoder config_file test_file outfile");
			System.out.println("num of args is "+ args.length);
			for(int i=0; i <args.length; i++)System.out.println("arg is: " + args[i]);
			System.exit(0);		
		}		
		String config_file = args[0].trim();
		String test_file = args[1].trim();
		String nbest_file = args[2].trim();
		
		//##### procedure: read config, init lm, init sym tbl, init models, read lm, read tm
		
		//##### read config file
		read_config_file(config_file);	
			
		if(use_remote_lm_server==false && use_srilm)
			   System.loadLibrary("srilm");//load once		
		
		init_lm_grammar();//inside, it will init sym tbl (and add global symbols)

		//##### initialize the models(need to read config file again)
		p_l_models = init_models(config_file,p_lm);

		init_tm_grammar();//create grammars
		
		//##### read LM grammar
		if(use_sent_specific_lm==false)load_lm_grammar_file(lm_file);
		
		//##### load TM grammar
		if(use_sent_specific_tm==false)load_tm_grammar_file(tm_file);
		
		//TODO ##### add default non-terminals
	    l_default_nonterminals= new ArrayList<Integer>();
		l_default_nonterminals.add(Symbol.add_non_terminal_symbol(default_non_terminal));		
		
		//###### statistics
		double t_sec = (System.currentTimeMillis()-start)/1000;
		if (logger.isLoggable(Level.INFO)) logger.info("before translation, loaddingtime is " +t_sec);

		//###### decode the sentences, maybe in parallel
		if(num_parallel_decoders==1){
			DiskHyperGraph d_hg = null;	
			KbestExtraction kbest_extrator = new KbestExtraction();
			if(save_disk_hg == true){d_hg = new DiskHyperGraph(); d_hg.init_write(nbest_file+".hg.items", forest_pruning, forest_pruning_threshold);}
			decode_a_file(test_file,nbest_file,0,d_hg,kbest_extrator);
			if(save_disk_hg == true)d_hg.write_rules_non_parallel(nbest_file + ".hg.rules");
		}else{//>1: parallel decoder
			if(use_remote_lm_server==true){//TODO
				if (logger.isLoggable(Level.SEVERE)) logger.severe("You cannot run parallel decoder and remote lm server together");
				System.exit(0);
			}
			run_parallel_decoder(test_file,nbest_file);	
		}
		
		//#### clean up
		p_lm.end_lm_grammar();//to end the threads
		t_sec = (System.currentTimeMillis()-start)/1000;
		if (logger.isLoggable(Level.INFO)) logger.info("Total running time is " + t_sec);
	}
		 
	public static void run_parallel_decoder(String test_file, String nbest_file){
		int n_lines = FileUtility.number_lines_in_file(test_file);
		double num_per_thread_double = n_lines*1.0/num_parallel_decoders;
		int num_per_thread= (int)num_per_thread_double;
		l_parallel_decode_threads=new Thread[num_parallel_decoders];
		l_parallel_test_files=new String[num_parallel_decoders];
		l_parallel_nbest_files=new String[num_parallel_decoders];
		l_parallel_disk_hgs = new DiskHyperGraph[num_parallel_decoders];
		l_parallel_kbest_extractors = new KbestExtraction[num_parallel_decoders];
		if (logger.isLoggable(Level.INFO)) logger.info("num_per_file_double: " + num_per_thread_double + "num_per_file_int: " + num_per_thread);
		BufferedReader t_reader_test = FileUtility.getReadFileStream(test_file,"UTF-8");
		
		//run the main control job
		int n_decoder_to_commit=1;
		String cur_test_file=parallel_files_prefix+".test."+n_decoder_to_commit;
		String cur_nbest_file=parallel_files_prefix+".nbest."+n_decoder_to_commit;
		BufferedWriter t_writer_test = FileUtility.getWriteFileStream(cur_test_file,"UTF-8");
		int sent_id=0;
		int start_sent_id= sent_id;
		
		String cn_sent;
		while((cn_sent=FileUtility.read_line_lzf(t_reader_test))!=null){
			sent_id++;
			FileUtility.write_lzf(t_writer_test,cn_sent+"\n");
			
			//make the Symbol table is finalized before running multiple threads, this is to avoid synchronization among threads
			String t_wrds[] =cn_sent.split("\\s+");for(int wt=0; wt<t_wrds.length; wt++)Symbol.add_terminal_symbol(t_wrds[wt]);//TODO	
			
			if(sent_id!=0 && 
			   n_decoder_to_commit < num_parallel_decoders && //we will include all additional lines into last file 
			   sent_id%num_per_thread==0){				
				//submit current job
				FileUtility.flush_lzf(t_writer_test);	
				FileUtility.close_write_file(t_writer_test);
				DiskHyperGraph dhg = null;
				if(save_disk_hg==true) { dhg = new  DiskHyperGraph(); dhg.init_write(cur_nbest_file+".hg.items", forest_pruning, forest_pruning_threshold);}
				KbestExtraction kbest_extrator = new KbestExtraction();
				ParallelDecoder pdecoder = new ParallelDecoder(cur_test_file, cur_nbest_file, start_sent_id, dhg,kbest_extrator);
				l_parallel_decode_threads[n_decoder_to_commit-1]=pdecoder;
				l_parallel_test_files[n_decoder_to_commit-1]=cur_test_file;
				l_parallel_nbest_files[n_decoder_to_commit-1]=cur_nbest_file;
				l_parallel_disk_hgs[n_decoder_to_commit-1]=dhg;
				l_parallel_kbest_extractors[n_decoder_to_commit-1]=kbest_extrator;
				
				//prepare next job
				start_sent_id=sent_id;
				n_decoder_to_commit++;
				cur_test_file=parallel_files_prefix+".test."+n_decoder_to_commit;
				cur_nbest_file=parallel_files_prefix+".nbest."+n_decoder_to_commit;
				t_writer_test = FileUtility.getWriteFileStream(cur_test_file,"UTF-8");					
			}
		}
		//####prepare the the last job
		FileUtility.flush_lzf(t_writer_test);
		FileUtility.close_write_file(t_writer_test);					
		DiskHyperGraph dhg = null;
		if(save_disk_hg==true) { dhg = new DiskHyperGraph(); dhg.init_write(cur_nbest_file+".hg.items", forest_pruning, forest_pruning_threshold);}
		KbestExtraction kbest_extrator = new KbestExtraction();
		ParallelDecoder pdecoder = new ParallelDecoder(cur_test_file, cur_nbest_file, start_sent_id, dhg,kbest_extrator);
		l_parallel_decode_threads[n_decoder_to_commit-1]=pdecoder;
		l_parallel_test_files[n_decoder_to_commit-1]=cur_test_file;
		l_parallel_nbest_files[n_decoder_to_commit-1]=cur_nbest_file;
		l_parallel_disk_hgs[n_decoder_to_commit-1]=dhg;		
		l_parallel_kbest_extractors[n_decoder_to_commit-1]=kbest_extrator;
		FileUtility.close_read_file(t_reader_test);
		
		//run all the jobs
		for(int i=0; i<l_parallel_decode_threads.length; i++){
			if (logger.isLoggable(Level.INFO)) logger.info("##############start thread " +i);
			l_parallel_decode_threads[i].start();
		}
		
		//### wait for the threads finish
		for(int i=0; i<l_parallel_decode_threads.length; i++){
    		 try {	        		 
    			 l_parallel_decode_threads[i].join();       
             } catch (InterruptedException e) {
            	 if (logger.isLoggable(Level.WARNING)) logger.warning("Warning: thread is interupted for server " + i);
             } 
    	}
		
		//#### merge the nbest files, and remove tmp files
		BufferedWriter t_writer_nbest = FileUtility.getWriteFileStream(nbest_file,"UTF-8");
		BufferedWriter t_writer_dhg_items = null;
		if(save_disk_hg) t_writer_dhg_items = FileUtility.getWriteFileStream(nbest_file+".hg.items","UTF-8");
		for(int i=0; i<l_parallel_decode_threads.length; i++){
			String sent;
			//merge nbest
			BufferedReader t_reader = FileUtility.getReadFileStream(l_parallel_nbest_files[i],"UTF-8");
			while((sent=FileUtility.read_line_lzf(t_reader))!=null){
				FileUtility.write_lzf(t_writer_nbest,sent+"\n");				
			}
			FileUtility.close_read_file(t_reader);	
			//TODO: remove the tem nbest file
			
			//merge hypergrpah items
			if(save_disk_hg){
				BufferedReader t_reader_dhg_items = FileUtility.getReadFileStream(l_parallel_nbest_files[i]+".hg.items","UTF-8");
				while((sent=FileUtility.read_line_lzf(t_reader_dhg_items))!=null){
					FileUtility.write_lzf(t_writer_dhg_items,sent+"\n");				
				}
				FileUtility.close_read_file(t_reader_dhg_items);	
				//TODO: remove the tem nbest file
			}
			
		}
		FileUtility.flush_lzf(t_writer_nbest);	
		FileUtility.close_write_file(t_writer_nbest);
		if(save_disk_hg){FileUtility.flush_lzf(t_writer_dhg_items);	FileUtility.close_write_file(t_writer_dhg_items);}
		
		//merge the grammar rules for disk hyper-graphs
		if(save_disk_hg){
			HashMap tbl_done = new HashMap();
			BufferedWriter t_writer_dhg_rules = FileUtility.getWriteFileStream(nbest_file+".hg.rules","UTF-8");
			for(DiskHyperGraph dhg2 : l_parallel_disk_hgs)
				dhg2.write_rules_parallel(t_writer_dhg_rules, tbl_done);
			FileUtility.flush_lzf(t_writer_dhg_rules);
			FileUtility.close_write_file(t_writer_dhg_rules);
		}
	}
	public static class ParallelDecoder extends Thread {
		 String test_file;
		 String nbest_file;
		 int start_sent_id; //start sent id
		 DiskHyperGraph dpg;
		 KbestExtraction kbest_extractor;
		  public ParallelDecoder( String test_file_in, String nbest_file_in, int start_sent_id_in, DiskHyperGraph dpg_in, KbestExtraction extractor ){
		    	test_file = test_file_in;
		    	nbest_file = nbest_file_in;
		    	start_sent_id = start_sent_id_in;
		    	dpg = dpg_in;
		    	kbest_extractor = extractor;
		  }
		    
		  public void run() {		    	
		   	decode_a_file(test_file, nbest_file, start_sent_id, dpg, kbest_extractor);
		  }
	}
	
	
	//TODO: log file is not properly handled for parallel decoding
	public static void decode_a_file(String test_file, String nbest_file, int start_sent_id, DiskHyperGraph dhg, KbestExtraction kbest_extractor){
		BufferedReader t_reader_test = FileUtility.getReadFileStream(test_file,"UTF-8");
		BufferedWriter t_writer_nbest = FileUtility.getWriteFileStream(nbest_file,"UTF-8");
		
		String cn_sent;
		int sent_id=start_sent_id;//if no sent tag, then this will be used
		while((cn_sent=FileUtility.read_line_lzf(t_reader_test))!=null){
			if (logger.isLoggable(Level.FINE)) logger.fine("now translate\n" + cn_sent);		
			int[] tem_id = new int[1];
			cn_sent  = get_sent_id(cn_sent, tem_id);			
			if(tem_id[0]>0)	sent_id = tem_id[0];
			if(use_sent_specific_lm==true){
				load_lm_grammar_file(g_sent_lm_file_name_prefix+sent_id+".gz");
			}
			if(use_sent_specific_tm==true){
				load_tm_grammar_file(g_sent_tm_file_name_prefix+sent_id+".gz");
			}
			
			translate(p_tm_grammars,p_l_models, cn_sent ,l_default_nonterminals, t_writer_nbest, sent_id, topN, dhg,kbest_extractor);
			sent_id++;
			//if(sent_id>10)break;
		}
		FileUtility.close_read_file(t_reader_test);
		FileUtility.flush_lzf(t_writer_nbest);		
		FileUtility.close_write_file(t_writer_nbest);
		
		//debug
		//g_con.print_confusion_tbl(f_confusion_grammar);
	}
	
		
	public static void load_lm_grammar_file(String new_lm_file){
		if (logger.isLoggable(Level.FINER)) logger.finer("############## reload lm from file" + new_lm_file);
		lm_file = new_lm_file;
		p_lm.read_lm_grammar_from_file(lm_file);		
	}

	public static void init_lm_grammar(){
		/*we assume there are only three possible configurations:
		 * (1) both lm and suffix are remote
		 * (2) both lm and suffix are local java
		 * (3) if local srilm is used, then we cannot use suffix related stuff 
		*/
		if(use_remote_lm_server==true){
			if(use_left_euqivalent_state==true || use_right_euqivalent_state==true){
				if (logger.isLoggable(Level.SEVERE)) logger.severe("use local srilm, we cannot use suffix/prefix stuff");
				System.exit(0);
			}
			p_lm = new LMGrammar_REMOTE(g_lm_order, remote_symbol_tbl, f_remote_server_list, num_remote_lm_servers);
		}else if(use_srilm==true){
			if(use_left_euqivalent_state==true || use_right_euqivalent_state==true){
				if (logger.isLoggable(Level.SEVERE)) logger.severe("use remote lm, we cannot use suffix/prefix stuff");
				System.exit(0);
			}				
			p_lm = new LMGrammar_SRILM(g_lm_order);
		}else{			
			//p_lm = new LMGrammar_JAVA(g_lm_order, lm_file, use_left_euqivalent_state);
			p_lm = new LMGrammar_JAVA(g_lm_order, use_left_euqivalent_state, use_right_euqivalent_state);
		}
	}
	
	public static void load_tm_grammar_file(String new_tm_file){
		if (logger.isLoggable(Level.FINER)) logger.finer("############## reload tm from file" + new_tm_file);
		tm_file=new_tm_file;		
		p_tm_grammars[0].read_tm_grammar_glue_rules();//glue grammar
		p_tm_grammars[1].read_tm_grammar_from_file(tm_file);//regular grammar
	}

	public static void init_tm_grammar(){
		//###### Initialize the regular TM grammar
		TMGrammar regular_gr = new TMGrammar_Memory(p_l_models, phrase_owner, span_limit, "^\\[[A-Z]+\\,[0-9]*\\]$", "[\\[\\]\\,0-9]+");
		
		//##### add the glue grammar	
		TMGrammar glue_gr = new TMGrammar_Memory(p_l_models, phrase_owner, -1, "^\\[[A-Z]+\\,[0-9]*\\]$", "[\\[\\]\\,0-9]+");
		p_tm_grammars = new TMGrammar[2];		
		p_tm_grammars[0]=glue_gr;
		p_tm_grammars[1]=regular_gr;
	}
	
	//translate a sentence
	static void translate(TMGrammar[] grs,ArrayList<FeatureFunction> l_models, String sentence, ArrayList<Integer> l_default_nonterminals, BufferedWriter t_writer_nbest, int sent_id, int topN, DiskHyperGraph dhg, KbestExtraction kbest_extractor){
		long start = System.currentTimeMillis();
		int[] sentence_numeric = Symbol.get_terminal_ids_for_sentence(sentence);
		
		Lattice<Integer> inputLattice; {
			
			Integer[] input = new Integer[sentence_numeric.length];
			for (int i=0; i<sentence_numeric.length; i++) {
				input[i] = sentence_numeric[i];
			}

			inputLattice = new Lattice<Integer>(input);
		}

		
		Chart chart = new Chart(inputLattice,l_models,sent_id);
		chart.seed(grs,l_default_nonterminals, sentence_numeric); 
		if (logger.isLoggable(Level.FINER)) logger.finer("after seed, time: " + (System.currentTimeMillis()-start)/1000);
		HyperGraph p_hyper_graph =  chart.expand();
		
		if (logger.isLoggable(Level.FINER)) logger.finer("after expand, time: " + (System.currentTimeMillis()-start)/1000);
		//p_hyper_graph.lazy_k_best_extract(l_models, topN, use_unique_nbest, sent_id,t_writer_nbest, use_tree_nbest,add_combined_cost);
		kbest_extractor.lazy_k_best_extract_hg(p_hyper_graph,l_models, topN, use_unique_nbest, sent_id,t_writer_nbest, use_tree_nbest,add_combined_cost);
		if (logger.isLoggable(Level.FINER)) logger.finer("after kbest, time: " + (System.currentTimeMillis()-start)/1000);	
		if(dhg!=null) dhg.save_hyper_graph(p_hyper_graph);
		
		//debug
		//g_con.get_confusion_in_hyper_graph_cell_specific(p_hyper_graph,p_hyper_graph.sent_len);
	}
		
	public static ArrayList<FeatureFunction>  init_models(String config_file, LMGrammar p_lm){
		BufferedReader t_reader_config = FileUtility.getReadFileStream(config_file,"UTF-8");
		String line;
		ArrayList<FeatureFunction> l_models =new ArrayList<FeatureFunction>();
		while((line=FileUtility.read_line_lzf(t_reader_config))!=null){
			//line = line.trim().toLowerCase();
			line = line.trim();
			if(  (line.matches("^\\s*\\#.*$")==true)//comment line
			   || (line.matches("^\\s*$")==true))//empty line
				continue;
			
			if(line.indexOf("=")==-1){//model weights				
				String[] fds = line.split("\\s+");		
				if(fds[0].compareTo("lm")==0 && fds.length == 2){//lm order weight
					double weight =(new Double(fds[1].trim())).doubleValue();
					p_lm_model = new LMModel(g_lm_order, p_lm, weight);
					l_models.add(p_lm_model);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("Line: %s\nAdd LM, order: %d; weight: %.3f;", line, g_lm_order, weight));
				}else if(fds[0].compareTo("phrasemodel")==0 && fds.length == 4){//phrasemodel owner column(0-indexed) weight
					int owner = Symbol.add_terminal_symbol(fds[1]);
					int column = (new Integer(fds[2].trim())).intValue();
					double weight =(new Double(fds[3].trim())).doubleValue();
					l_models.add(new PhraseModelFF(weight, owner, column));
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("Process Line: %s\nAdd PhraseModel, owner: %s; column: %d; weight: %.3f", line, owner, column, weight));
				}else if(fds[0].compareTo("arityphrasepenalty")==0 && fds.length == 5){//arityphrasepenalty owner start_arity end_arity weight
					int owner = Symbol.add_terminal_symbol(fds[1]);
					int start_arity = (new Integer(fds[2].trim())).intValue();
					int end_arity = (new Integer(fds[3].trim())).intValue();
					double weight =(new Double(fds[4].trim())).doubleValue();
					l_models.add(new ArityPhrasePenaltyFF(weight, owner, start_arity, end_arity));
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("Process Line: %s\nAdd ArityPhrasePenalty, owner: %s; start_arity: %d; end_arity: %d; weight: %.3f", line, owner, start_arity, end_arity, weight));
				}else if(fds[0].compareTo("wordpenalty")==0 && fds.length == 2){//wordpenalty weight
					double weight =(new Double(fds[1].trim())).doubleValue();
					l_models.add(new WordPenaltyFF(weight));
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("Process Line: %s\nAdd WordPenalty, weight: %.3f", line, weight));
				}else{
					if (logger.isLoggable(Level.SEVERE)) logger.severe("Wrong config line: " + line);
					System.exit(0);
				}
			}
		}
		FileUtility.close_read_file(t_reader_config);
		return l_models;
	}
	
	public static void  read_config_file(String config_file){
		BufferedReader t_reader_config = FileUtility.getReadFileStream(config_file,"UTF-8");
		String line;
		while((line=FileUtility.read_line_lzf(t_reader_config))!=null){
			//line = line.trim().toLowerCase();
			line = line.trim();
			if(  (line.matches("^\\s*\\#.*$")==true)//comment line
			   || (line.matches("^\\s*$")==true))//empty line
				continue;
			
			if(line.indexOf("=")!=-1){//parameters
				String[] fds = line.split("\\s*=\\s*");
				if(fds.length!=2){
					if (logger.isLoggable(Level.SEVERE)) logger.severe("Wrong config line: " + line);
					System.exit(0);
				}
				
				if(fds[0].compareTo("lm_file")==0){
					lm_file = fds[1].trim();
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("lm file: %s", lm_file));
				}else if(fds[0].compareTo("tm_file")==0){
					tm_file = fds[1].trim();
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("tm file: %s", tm_file));
				}else if(fds[0].compareTo("use_srilm")==0){
					use_srilm = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("use_srilm: %s", use_srilm));
				}else if(fds[0].compareTo("lm_ceiling_cost")==0){
					lm_ceiling_cost = new Double(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("lm_ceiling_cost: %s", lm_ceiling_cost));
				}else if(fds[0].compareTo("use_left_euqivalent_state")==0){
					use_left_euqivalent_state = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("use_left_euqivalent_state: %s", use_left_euqivalent_state));
				}else if(fds[0].compareTo("use_right_euqivalent_state")==0){
					use_right_euqivalent_state = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("use_right_euqivalent_state: %s", use_right_euqivalent_state));
				}else if(fds[0].compareTo("order")==0){
					g_lm_order = new Integer(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("g_lm_order: %s", g_lm_order));
				}else if(fds[0].compareTo("use_sent_specific_lm")==0){
					use_sent_specific_lm = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("use_sent_specific_lm: %s", use_sent_specific_lm));
				}else if(fds[0].compareTo("sent_lm_file_name_prefix")==0){
					g_sent_lm_file_name_prefix = fds[1].trim();
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("sent_lm_file_name_prefix: %s", g_sent_lm_file_name_prefix));
				}else if(fds[0].compareTo("use_sent_specific_tm")==0){
					use_sent_specific_tm = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("use_sent_specific_tm: %s", use_sent_specific_tm));
				}else if(fds[0].compareTo("sent_tm_file_name_prefix")==0){
					g_sent_tm_file_name_prefix = fds[1].trim();
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("sent_tm_file_name_prefix: %s", g_sent_tm_file_name_prefix));
				}else if(fds[0].compareTo("span_limit")==0){
					span_limit = new Integer(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("span_limit: %s", span_limit));
				}else if(fds[0].compareTo("phrase_owner")==0){
					phrase_owner = fds[1].trim();
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("phrase_owner: %s", phrase_owner));
				}else if(fds[0].compareTo("mono_owner")==0){
					mono_owner = fds[1].trim();
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("mono_owner: %s", mono_owner));
				}else if(fds[0].compareTo("begin_mono_owner")==0){
					begin_mono_owner = fds[1].trim();
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("begin_mono_owner: %s", begin_mono_owner));
				}else if(fds[0].compareTo("default_non_terminal")==0){
					default_non_terminal = fds[1].trim();
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("default_non_terminal: %s", default_non_terminal));
				}else if(fds[0].compareTo("fuzz1")==0){
					fuzz1 = new Double(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("fuzz1: %s", fuzz1));
				}else if(fds[0].compareTo("fuzz2")==0){
					fuzz2 = new Double(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("fuzz2: %s", fuzz2));
				}else if(fds[0].compareTo("max_n_items")==0){
					max_n_items = new Integer(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("max_n_items: %s", max_n_items));
				}else if(fds[0].compareTo("relative_threshold")==0){
					relative_threshold = new Double(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("relative_threshold: %s", relative_threshold));
				}else if(fds[0].compareTo("max_n_rules")==0){
					max_n_rules = new Integer(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("max_n_rules: %s", max_n_rules));
				}else if(fds[0].compareTo("rule_relative_threshold")==0){
					rule_relative_threshold = new Double(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("rule_relative_threshold: %s", rule_relative_threshold));
				}else if(fds[0].compareTo("use_unique_nbest")==0){
					use_unique_nbest = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("use_unique_nbest: %s", use_unique_nbest));
				}else if(fds[0].compareTo("add_combined_cost")==0){
					add_combined_cost = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("add_combined_cost: %s", add_combined_cost));
				}else if(fds[0].compareTo("use_tree_nbest")==0){
					use_tree_nbest = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("use_tree_nbest: %s", use_tree_nbest));
				}else if(fds[0].compareTo("top_n")==0){
					topN = new Integer(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("topN: %s", topN));
				}else if(fds[0].compareTo("use_remote_lm_server")==0){
					use_remote_lm_server = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("use_remote_lm_server: %s", use_remote_lm_server));
				}else if(fds[0].compareTo("f_remote_server_list")==0){
					f_remote_server_list = new String(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("f_remote_server_list: %s", f_remote_server_list));
				}else if(fds[0].compareTo("num_remote_lm_servers")==0){
					num_remote_lm_servers = new Integer(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("num_remote_lm_servers: %s", num_remote_lm_servers));
				}else if(fds[0].compareTo("remote_symbol_tbl")==0){
					remote_symbol_tbl = new String(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("remote_symbol_tbl: %s", remote_symbol_tbl));
				}else if(fds[0].compareTo("remote_lm_server_port")==0){
					//port = new Integer(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("remote_lm_server_port: not used"));					
				}else if(fds[0].compareTo("parallel_files_prefix")==0){
					parallel_files_prefix = new String(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("parallel_files_prefix: %s", parallel_files_prefix));
				}else if(fds[0].compareTo("num_parallel_decoders")==0){
					num_parallel_decoders = new Integer(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("num_parallel_decoders: %s", num_parallel_decoders));
				}else if(fds[0].compareTo("save_disk_hg")==0){
					save_disk_hg = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("save_disk_hg: %s", save_disk_hg));
				}else if(fds[0].compareTo("forest_pruning")==0){
					forest_pruning = new Boolean(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("forest_pruning: %s", forest_pruning));
				}else if(fds[0].compareTo("forest_pruning_threshold")==0){
					forest_pruning_threshold = new Double(fds[1]);
					if (logger.isLoggable(Level.FINEST)) logger.finest(String.format("forest_pruning_threshold: %s", forest_pruning_threshold));
				}else{
					if (logger.isLoggable(Level.SEVERE)) logger.severe("Wrong config line: " + line);
					System.exit(0);
				}
			}
		}
		FileUtility.close_read_file(t_reader_config);
	}
	
	//return sent without the tag
	//if no sent id, then return -1 in sent_id[]
    static String get_sent_id(String sent, int[] sent_id){
            if(sent.matches("^<seg\\s+id=.*$")){//havd sent id
                    String res_sent = sent.replaceAll("^<seg\\s+id=\"", "");
                    String str_id ="";
                    for(int i=0; i<res_sent.length(); i++){
                            char cur = res_sent.charAt(i);
                            if(cur != '"') str_id += cur; else break;
                    }
                    int res_id= (new Integer(str_id)).intValue();
                    res_sent = res_sent.replaceFirst(str_id+"\">", "");
                    res_sent = res_sent.replaceAll("</seg>", "");
                    sent_id[0]=res_id;
                    return res_sent;
            }else{  sent_id[0]=-1;return sent;}
    }
}

