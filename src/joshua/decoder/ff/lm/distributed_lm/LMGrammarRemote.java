/* This file is part of the Joshua Machine Translation System.
 * 
 * Joshua is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */
package joshua.decoder.ff.lm.distributed_lm;

import joshua.decoder.ff.lm.AbstractLM;
import joshua.decoder.Support;
import joshua.corpus.SymbolTable;
import joshua.util.FileUtility;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Hashtable;

/**
 * this class implement 
 * (1) get the list of lm servers
 * (2) setup network connection
 * (3) get lm probablity for n-gram remotely 
 * 
 * @author Zhifei Li, <zhifei.work@gmail.com>
 * @version $LastChangedDate: 2008-10-02 02:02:16 -0400 (星期四, 02 十月 2008) $
 */
//PATH: this => LMClient => network => LMServer => LMGrammar => LMGrammar_JAVA/SRILM; and then reverse the path
public class LMGrammarRemote extends AbstractLM {
	
	// if remote method is used
	private LMClient p_lm_client = null;
	
	//!!! we assume both suffix and lm are remoted, if one is remoted
	public LMGrammarRemote(SymbolTable psymbolTable, int order, String f_server_lists, int num_servers)
	throws IOException {
		super(psymbolTable, order);
		
		System.out.println("use remote suffix and lm server");
		String[] hosts   = new String[num_servers];
		int[]    ports   = new int[num_servers];
		double[] weights = new double[num_servers];
		
		read_lm_server_lists(f_server_lists, num_servers, hosts, ports,weights);
		if (1 == num_servers) {
			p_lm_client = new LMClientSingle(hosts[0], ports[0]);
			
			/*
			//debug begin
			System.out.println("begin to call msrlm");
			System.out.println("prob is " + ((LMClient_Single)p_lm_client).get_prob_msrlm("i am ok", 3));
			System.out.println("prob is " + ((LMClient_Single)p_lm_client).get_prob_msrlm("china is with", 3));
			System.out.println("prob is " + ((LMClient_Single)p_lm_client).get_prob_msrlm("china is a", 3));
			System.out.println("prob is " + ((LMClient_Single)p_lm_client).get_prob_msrlm("the chinese government", 3));
			
			System.exit(0);
			//end
			*/
		} else {
			p_lm_client = new LMClientMultiServer(hosts, ports, weights, num_servers);
		}
	}
	
	private void end_lm_grammar() {
		p_lm_client.close_client();
	}
	
	
	// format: lm_file host port weight
	private void read_lm_server_lists(String f_server_lists, int num_servers, String[] l_lm_server_hosts, int[] l_lm_server_ports, double[] l_lm_server_weights) throws IOException {
		
		BufferedReader t_reader = 
			FileUtility.getReadFileStream(f_server_lists);
		String line;
		int count = 0;
		while ((line = FileUtility.read_line_lzf(t_reader)) != null) {
			String fname = line.trim();
			Hashtable<String,?> res_conf = read_config_file(fname);
			
			String lm_file = (String)  res_conf.get("lm_file");
			String host    = (String)  res_conf.get("hostname");
			int    port    = (Integer) res_conf.get("port");
			double weight  = (Double)  res_conf.get("weight");
			
			l_lm_server_hosts[count]   = host;
			l_lm_server_ports[count]   = port;
			l_lm_server_weights[count] = weight;
			count++;
			System.out.println("lm server: "
				+ "lm_file: " + lm_file
				+ "; host: " + host
				+ "; port: " + port
				+ "; weight: " + weight);
		}
		if (count != num_servers) {
			System.out.println("num of lm servers does not match");
			System.exit(1);
		}
		t_reader.close();
	}
	
	
	@SuppressWarnings("unchecked")
	private static Hashtable<String,?> read_config_file(String config_file)
	throws IOException {
		
		Hashtable res = new Hashtable();
		BufferedReader t_reader_config = 
			FileUtility.getReadFileStream(config_file);
		String line;
		while ((line = FileUtility.read_line_lzf(t_reader_config)) != null) {
			//line = line.trim().toLowerCase();
			line = line.trim();
			if (line.matches("^\\s*(?:\\#.*)?$")) continue;
			
			if (-1 != line.indexOf("=")) { // parameters
				String[] fds = line.split("\\s*=\\s*");
				if (fds.length != 2) {
					Support.write_log_line("Wrong config line: " + line, Support.ERROR);
					System.exit(1);
				}
				if (0 == fds[0].compareTo("lm_file")) {
					String lm_file = fds[1].trim();
					res.put("lm_file", lm_file);
					System.out.println(String.format("lm file: %s", lm_file));
					
				} else if (0 == fds[0].compareTo("remote_lm_server_port")) {
					int port = new Integer(fds[1]);
					res.put("port", port);
					System.out.println(String.format("remote_lm_server_port: %s", port));
					
				} else if (0 == fds[0].compareTo("hostname")) {
					String host_name = fds[1].trim();
					res.put("hostname", host_name);
					System.out.println(String.format("host name is: %s", host_name));
					
				} else if (0 == fds[0].compareTo("interpolation_weight")) {
					double interpolation_weight = new Double(fds[1]);
					res.put("weight", interpolation_weight);
					System.out.println(String.format("interpolation_weightt: %s", interpolation_weight));
					
				} else {
					Support.write_log_line("Warning: not used config line: " + line, Support.ERROR);
					//System.exit(1);
				}
			}
		}
		t_reader_config.close();
		return res;
	}
	
	
	
	//this should be called by decoder only
	protected double ngramLogProbability_helper(int[] ngram, int order) {
		return p_lm_client.get_prob(ngram, ngram.length);
	}
	
	
	protected double logProbabilityOfBackoffState_helper(
		int[] ngram, int order, int qtyAdditionalBackoffWeight
	) {
		throw new UnsupportedOperationException("probabilityOfBackoffState_helper undefined for distributed_lm");
	}
	
	
	public void write_vocab_map_srilm(String fname) {
		System.out.println("Error: call write_vocab_map_srilm in remote, must exit");
		System.exit(1);
	}
}
