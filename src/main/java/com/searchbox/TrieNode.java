/*******************************************************************************
 * Copyright Searchbox - http://www.searchbox.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.searchbox;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * @author andrew
 */
public class TrieNode implements Serializable {

	static final long serialVersionUID = SuggesterComponentParams.serialVersionUID;
	private static Logger LOGGER = LoggerFactory.getLogger(TrieNode.class);
	private HashMap<Character, TrieNode> children;
	private HashMap<String, Double> phrases;
	public long termfreq = 0;
	public long docfreq = 0;
	NormalizeCount nc;
	private int ngram;
	private int numdocs;
	String myval;

	public TrieNode(String prefix, int NGRAM) {
		this.myval = prefix;
		ngram = NGRAM;
	}

	public boolean containsChildValue(char c) {
		if (children == null) {
			return false;
		}
		return children.containsKey(c);
	}

	public TrieNode getChild(char c) {
		return children.get(c);
	}

	public void addNode(char c, TrieNode t) {
		if (children == null) {
			children = new HashMap<Character, TrieNode>();
		}
		children.put(c, t);
	}

	public double AddPhraseIncrementCount(String phrase, double defvalue) {
		if (phrases == null) {
			phrases = new HashMap<String, Double>();
		}
		Double count = phrases.containsKey(phrase) ? phrases.get(phrase)
				: defvalue;
		phrases.put(phrase, count + 1);
		return count + 1;

	}

	public TrieNode AddString(String val) {
		TrieNode head = this;
		for (char c : val.toCharArray()) {
			if (head.containsChildValue(c)) {
				head = head.getChild(c);
			} else {
				TrieNode temp = this;
				temp = new TrieNode(head.myval + c, ngram);
				head.addNode(c, temp);
				head = temp;
				// mem-leak-check
				temp = null;
			}
		}
		return head;
	}

	public NormalizeCount computeNormalizeTerm(int level, int numdocs) {
		if (nc == null) {
			nc = new NormalizeCount(this.ngram);
		}
		// Logger.info("level\t"+level);
		// normolization as according to equation (10)
		if (docfreq > 0) {
			this.numdocs = numdocs;
			nc.termnormfactor += termfreq
					* Math.log10((numdocs * 1.0) / docfreq);
		}
		doPhraseTotalFreq();
		if (children != null) {
			for (TrieNode child : children.values()) {
				NormalizeCount lnc = child.computeNormalizeTerm(level + 1,
						numdocs);
				nc.add(lnc);
			}
		}
		return nc;
	}

	private void doPhraseTotalFreq() {
		if (phrases != null) {
			for (Double f : phrases.values()) {
				// check right side of decimal to get ngram
				int gram = (int) (Math.round((f - Math.floor(f)) * 10.0) - 1);
				nc.ngramfreq[gram] += Math.floor(f);
				// compute total number of n-gram seen
				nc.ngramnum[gram]++;
			}
		}

	}

	SuggestionResultSet computeQt(String partialToken, int maxnumphrases) {
		TrieNode current = this;
		for (char c : partialToken.toCharArray()) {
			// compute Q_T as per equation (1)
			if (current.containsChildValue(c)) {
				current = current.getChild(c);
			} else {
				// not found?
				return null;
			}
		}
		return current.recurse(current.nc.termnormfactor, maxnumphrases);
	}

	private SuggestionResultSet recurse(double termnormfactor, int maxnumphrases) {
		SuggestionResultSet srs = new SuggestionResultSet(myval, maxnumphrases);

		// this term exists as a whole
		if (phrases != null && phrases.size() > 0) {
			double p_ci_qt = (termfreq * Math
					.log10((1.0 * numdocs) / (docfreq))) / (termnormfactor);
			// add his phrases and whatnot
			srs.add(phrases, p_ci_qt, nc.phrasenormfactor);
		}
		if (children != null) {

			// this term also exists as a partial
			for (TrieNode child : children.values()) {
				srs.add(child.recurse(termnormfactor, maxnumphrases));
			}
		}
		return srs;
	}

	void computeNormalizePhrase(double[] logavgfreq) {
		if (phrases != null) {
			for (String phrase : phrases.keySet()) {
				// Logger.info(phrase + "\t" + phrases.get(phrase));

				// normalization as per equation (11)
				double val = phrases.get(phrase);
				int gram = (int) Math.round((val - Math.floor(val)) * 10.0) - 1;
				double newval = Math.floor(val) / logavgfreq[gram];
				nc.phrasenormfactor += newval;
				phrases.put(phrase, newval);
			}
		}
		if (children != null) {
			for (TrieNode child : children.values()) {
				child.computeNormalizePhrase(logavgfreq);
			}
		}
		// return nc;
	}

	/**
	 * after creating the tree its massive, and most likely filled with a lot of
	 * junk, so we iterate thorugh all of it and look for things with low counts
	 * and remove them
	 */
	void prune(int minDocFreq, int minTermFreq, HashSet<String> nonprune) {

		// we're not going to remove it if it comes from the file
		if (!nonprune.contains(myval)
				&& (this.termfreq < minTermFreq || this.docfreq < minDocFreq)) {
			this.termfreq = 0;
			this.docfreq = 0;
			// no term, no phrases
			phrases = null;
		}

		if (children != null) {
			for (TrieNode child : children.values()) {
				child.prune(minDocFreq, minTermFreq, nonprune);
			}
		}
	}

	public class NormalizeCount implements Serializable {

		static final long serialVersionUID = SuggesterComponentParams.serialVersionUID;
		public double termnormfactor = 0;
		public double phrasenormfactor = 0;
		public double ngramnum[];
		public double ngramfreq[];

		public NormalizeCount(int NGRAM) {
			ngramnum = new double[NGRAM];
			ngramfreq = new double[NGRAM];
		}

		public void add(NormalizeCount in) {
			if (in != null) {
				this.termnormfactor += in.termnormfactor;
				for (int zz = 0; zz < ngramnum.length; zz++) {
					ngramfreq[zz] += in.ngramfreq[zz];
					ngramnum[zz] += in.ngramnum[zz];
				}
			}
		}
	}
}
