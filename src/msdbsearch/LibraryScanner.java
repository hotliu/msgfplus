package msdbsearch;

import java.io.DataInputStream;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Map.Entry;

import parser.BufferedLineReader;

import msgf.DeNovoGraph;
import msgf.FlexAminoAcidGraph;
import msgf.MSGFDBResultGenerator;
import msgf.GeneratingFunction;
import msgf.GeneratingFunctionGroup;
import msgf.NominalMass;
import msgf.Tolerance;
import msscorer.SimpleDBSearchScorer;
import msutil.AminoAcid;
import msutil.AminoAcidSet;
import msutil.Composition;
import msutil.Enzyme;
import msutil.Modification;
import msutil.Peptide;
import msutil.SpecKey;
import msutil.Modification.Location;
import sequences.Constants;

public class LibraryScanner {

	private final int MAX_LIBRARY_PEPTIDE_LENGTH = 100;
	
	private AminoAcidSet aaSet = AminoAcidSet.getStandardAminoAcidSet();
	private double[] aaMass;
	private int[] intAAMass;
	
	private int numPeptidesPerSpec;

	// Input spectra
	private final ScoredSpectraMap specScanner;
	
	// DB search results
	private Map<SpecKey,PriorityQueue<DatabaseMatch>> specKeyDBMatchMap;
	private Map<Integer,PriorityQueue<DatabaseMatch>> specIndexDBMatchMap;

	// For output
	private String threadName = "";
	public LibraryScanner(
			ScoredSpectraMap specScanner,
			int numPeptidesPerSpec
			) 
	{
		this.specScanner = specScanner;
		this.numPeptidesPerSpec = numPeptidesPerSpec;
		
		// Initialize mass arrays for a faster search
		aaMass = new double[aaSet.getMaxResidue()];
		intAAMass = new int[aaSet.getMaxResidue()];
		for(int i=0; i<aaMass.length; i++)
		{
			aaMass[i] = -1;
			intAAMass[i] = -1;
		}
		for(AminoAcid aa : aaSet.getAllAminoAcidArr())
		{
			aaMass[aa.getResidue()] = aa.getAccurateMass();
			intAAMass[aa.getResidue()] = aa.getNominalMass();
		}
		
		specKeyDBMatchMap = Collections.synchronizedMap(new HashMap<SpecKey,PriorityQueue<DatabaseMatch>>());
		specIndexDBMatchMap = Collections.synchronizedMap(new HashMap<Integer,PriorityQueue<DatabaseMatch>>());
	}

	public LibraryScanner setThreadName(String threadName)
	{
		this.threadName = threadName;
		return this;
	}
	
	public synchronized void addDBMatches(Map<SpecKey,PriorityQueue<DatabaseMatch>> map)
	{
		if(map == null)
			return;
		Iterator<Entry<SpecKey, PriorityQueue<DatabaseMatch>>> itr = map.entrySet().iterator();
		while(itr.hasNext())
		{
			Entry<SpecKey, PriorityQueue<DatabaseMatch>> entry = itr.next();
			SpecKey specKey = entry.getKey(); 
			PriorityQueue<DatabaseMatch> queue = specKeyDBMatchMap.get(entry.getKey());
			if(queue == null)
			{
				queue = new PriorityQueue<DatabaseMatch>();
				specKeyDBMatchMap.put(specKey, queue);
			}
			for(DatabaseMatch match : entry.getValue())
			{
				if(queue.size() < this.numPeptidesPerSpec)
				{
					queue.add(match);
				}
				else if(queue.size() >= this.numPeptidesPerSpec)
				{
					if(match.getScore() > queue.peek().getScore())
					{
						queue.poll();
						queue.add(match);
					}
				}
			}
		}
	}
	
	public void libSearch(BufferedLineReader in, boolean verbose)
	{
		Map<SpecKey,PriorityQueue<DatabaseMatch>> curSpecKeyDBMatchMap = new HashMap<SpecKey,PriorityQueue<DatabaseMatch>>();
		
		String s;
		
		// read the number of distinct peptide ions
		int numDistinctPeptideIons = 0;
		String keyword = "Total number of distinct ions in library";
		while((s=in.readLine()) != null)
		{
			if(s.startsWith("#") && s.contains(keyword))
			{
				String[] token = s.split("\\s+");
				numDistinctPeptideIons = Integer.parseInt(token[token.length-1]);
				break;
			}
			if(s.startsWith("#") && s.contains("==="))
				break;
		}
		
		if(numDistinctPeptideIons == 0)
		{
			System.err.println("Wrong pepidx file!");
			System.exit(-1);
		}
		
		int numPeptides = 0;
		while((s=in.readLine()) != null)
		{
			if(s.length() == 0 || s.startsWith("#"))
				continue;
			
			String[] token = s.split("\\s+");
			if(token.length != 3)
				continue;
			
			// Print out the progress
			if(verbose && numPeptides % 100000 == 0)
			{
				System.out.print(threadName + ": Database search progress... "); 
				System.out.format("%.1f%% complete\n", numPeptides/(float)numDistinctPeptideIons*100);
			}

			String pepStr = token[0];
			String pepInfoStr = token[1];
			
			String[] tokenInfo = pepInfoStr.split("\\|");
			int charge = Integer.parseInt(tokenInfo[0]);
			
			// extract modification info, 1-based
			double[] modMass = new double[MAX_LIBRARY_PEPTIDE_LENGTH];
			int[] nominalModMass = new int[MAX_LIBRARY_PEPTIDE_LENGTH];
			String[] modResidues = new String[MAX_LIBRARY_PEPTIDE_LENGTH];
			
			String modInfo = tokenInfo[1];
			String[] tokenMod = modInfo.split("/");
			int numMods = Integer.parseInt(tokenMod[0]);
			for(int i=1; i<tokenMod.length; i++)
			{
				String[] mod = tokenMod[i].split(",");
				int location = Integer.parseInt(mod[0]);	// 0-base
				if(location == -1)
					location = 0;
//				char residue = mod[1].charAt(0);
				String modName = mod[2];
				double deltaMass = modTable.get(modName);
				modMass[location+1] = deltaMass;
				nominalModMass[location+1] = NominalMass.toNominalMass((float)deltaMass);
				modResidues[location+1] = modResidueTable.get(modName);
			}
				
			// always 0 at index 0, mass of ith prefix at index i
			int[] nominalPRM = new int[MAX_LIBRARY_PEPTIDE_LENGTH];
			double[] prm = new double[MAX_LIBRARY_PEPTIDE_LENGTH];
			
			nominalPRM[0] = 0;
			prm[0] = 0;
			StringBuffer peptideOutput = new StringBuffer();
			int pepLength = peptideOutput.length();
			for(int i=0; i<pepLength; i++)	// ith character of a peptide (base 0)
			{
				char residue = pepStr.charAt(i);
				nominalPRM[i+1] = nominalPRM[i] + intAAMass[residue] + nominalModMass[i+1];
				prm[i+1] = prm[i] + aaMass[residue] + modMass[i+1];
				peptideOutput.append(modResidues[i+1]);
			}
			
			float peptideMass = (float)prm[pepLength];
			int nominalPeptideMass = nominalPRM[pepLength];
			float tolDaLeft = specScanner.getLeftParentMassTolerance().getToleranceAsDa(peptideMass);
			float tolDaRight = specScanner.getRightParentMassTolerance().getToleranceAsDa(peptideMass);
					
			double leftThr = (double)(peptideMass - tolDaRight);
			double rightThr = (double)(peptideMass + tolDaLeft);
			Collection<SpecKey> matchedSpecKeyList = specScanner.getPepMassSpecKeyMap().subMap(leftThr, rightThr).values();
			for(SpecKey specKey : matchedSpecKeyList)
			{
				if(charge != specKey.getCharge())
					continue;
				SimpleDBSearchScorer<NominalMass> scorer = specScanner.getSpecKeyScorerMap().get(specKey);
				int score = scorer.getScore(prm, nominalPRM, 1, pepLength+1, numMods); 
				PriorityQueue<DatabaseMatch> prevMatchQueue = curSpecKeyDBMatchMap.get(specKey);
				if(prevMatchQueue == null)
				{
					prevMatchQueue = new PriorityQueue<DatabaseMatch>();
					curSpecKeyDBMatchMap.put(specKey, prevMatchQueue);
				}
				if(prevMatchQueue.size() < this.numPeptidesPerSpec)
				{
					prevMatchQueue.add(new DatabaseMatch(0, pepLength, score, nominalPeptideMass, peptideOutput.toString()));
				}
				else if(prevMatchQueue.size() >= this.numPeptidesPerSpec)
				{
					if(score > prevMatchQueue.peek().getScore())
					{
						prevMatchQueue.poll();
						prevMatchQueue.add(new DatabaseMatch(0, pepLength, score, nominalPeptideMass, peptideOutput.toString()));
					}
				}
			}
		}
		
		this.addDBMatches(curSpecKeyDBMatchMap);
	}  		
	
	public void computeSpecProb(boolean storeScoreDist)
	{
		computeSpecProb(storeScoreDist, 0, specScanner.getSpecKeyList().size());
	}
	
	public void computeSpecProb(boolean storeScoreDist, int fromIndex, int toIndex)
	{
		List<SpecKey> specKeyList = specScanner.getSpecKeyList().subList(fromIndex, toIndex);
		
		int numSpecs = toIndex-fromIndex;
		int numProcessedSpecs = 0;
		for(SpecKey specKey : specKeyList)
		{
			numProcessedSpecs++;
			if(numProcessedSpecs % 1000 == 0)
			{
				System.out.print(threadName + ": Computing spectral probabilities... "); 
				System.out.format("%.1f%% complete\n", numProcessedSpecs/(float)numSpecs*100);
			}
			
			PriorityQueue<DatabaseMatch> matchQueue = specKeyDBMatchMap.get(specKey);
			if(matchQueue == null)
				continue;

			int specIndex = specKey.getSpecIndex();
			int minScore = Integer.MAX_VALUE;
			for(DatabaseMatch m : matchQueue)
			{
				if(m.getScore() < minScore)
					minScore = m.getScore();
			}
			
			GeneratingFunctionGroup<NominalMass> gf = new GeneratingFunctionGroup<NominalMass>();
			SimpleDBSearchScorer<NominalMass> scoredSpec = specScanner.getSpecKeyScorerMap().get(specKey);
			float peptideMass = scoredSpec.getPrecursorPeak().getMass() - (float)Composition.H2O;
			int nominalPeptideMass = NominalMass.toNominalMass(peptideMass);
			float tolDaLeft = specScanner.getLeftParentMassTolerance().getToleranceAsDa(peptideMass);
			float tolDaRight = specScanner.getRightParentMassTolerance().getToleranceAsDa(peptideMass);
			int maxPeptideMassIndex, minPeptideMassIndex;
			maxPeptideMassIndex = nominalPeptideMass + Math.round(tolDaLeft-0.4999f);
			minPeptideMassIndex = nominalPeptideMass - Math.round(tolDaRight-0.4999f);
			
			if(tolDaRight < 0.5f)
				minPeptideMassIndex -= specScanner.getNumAllowedC13();

			for(int peptideMassIndex = minPeptideMassIndex; peptideMassIndex<=maxPeptideMassIndex; peptideMassIndex++)
			{
				DeNovoGraph<NominalMass> graph = new FlexAminoAcidGraph(
						aaSet, 
						peptideMassIndex,
						Enzyme.TRYPSIN,
						scoredSpec,
						true,
						false
						);
				
				GeneratingFunction<NominalMass> gfi = new GeneratingFunction<NominalMass>(graph)
				.doNotBacktrack()
				.doNotCalcNumber();
				gfi.setUpScoreThreshold(minScore);
				gf.registerGF(graph.getPMNode(), gfi);
			}

			boolean isGFComputed = gf.computeGeneratingFunction();
			
			for(DatabaseMatch match : matchQueue)
			{
				if(!isGFComputed || match.getNominalPeptideMass() < minPeptideMassIndex || match.getNominalPeptideMass() > maxPeptideMassIndex)
				{
					match.setDeNovoScore(Integer.MIN_VALUE);
					match.setSpecProb(1);
				}
				else
				{
					match.setDeNovoScore(gf.getMaxScore()-1);
					int score = match.getScore();
					double specProb = gf.getSpectralProbability(score);
					assert(specProb > 0): specIndex + ": " + match.getDeNovoScore()+" "+match.getScore()+" "+specProb; 
					match.setSpecProb(specProb);
					if(storeScoreDist)
						match.setScoreDist(gf.getScoreDist());
				}
			}
		}
	}
	
	public synchronized void addDBSearchResults(List<MSGFDBResultGenerator.DBMatch> gen, String specFileName, boolean replicateMergedResults)
	{
		Iterator<Entry<SpecKey, PriorityQueue<DatabaseMatch>>> itr = specKeyDBMatchMap.entrySet().iterator();
		while(itr.hasNext())
		{
			Entry<SpecKey, PriorityQueue<DatabaseMatch>> entry = itr.next();
			SpecKey specKey = entry.getKey();
			PriorityQueue<DatabaseMatch> matchQueue = entry.getValue();
			if(matchQueue == null || matchQueue.size() == 0)
				continue;
			
			int specIndex = specKey.getSpecIndex();
			int charge = specKey.getCharge();
			PriorityQueue<DatabaseMatch> existingQueue = specIndexDBMatchMap.get(specIndex);
			if(existingQueue == null)
			{
				existingQueue = new PriorityQueue<DatabaseMatch>(this.numPeptidesPerSpec, new DatabaseMatch.SpecProbComparator());
				specIndexDBMatchMap.put(specIndex, existingQueue);
			}
			
			for(DatabaseMatch match : matchQueue)
			{
				match.setCharge(charge);
				if(existingQueue.size() < this.numPeptidesPerSpec)
				{
					existingQueue.add(match);
				}
				else if(existingQueue.size() >= this.numPeptidesPerSpec)
				{
					if(match.getSpecProb() < existingQueue.peek().getSpecProb())
					{
						existingQueue.poll();
						existingQueue.add(match);
					}
				}
			}
		}		
		
		Iterator<Entry<Integer, PriorityQueue<DatabaseMatch>>> itr2 = specIndexDBMatchMap.entrySet().iterator();
		while(itr2.hasNext())
		{
			Entry<Integer, PriorityQueue<DatabaseMatch>> entry = itr2.next();
			int specIndex = entry.getKey();
			PriorityQueue<DatabaseMatch> matchQueue = entry.getValue();
			if(matchQueue == null)
				continue;

			ArrayList<DatabaseMatch> matchList = new ArrayList<DatabaseMatch>(matchQueue);
			if(matchList.size() == 0)
				continue;

			for(int i=matchList.size()-1; i>=0; --i)
			{
				DatabaseMatch match = matchList.get(i);
				
				if(match.getDeNovoScore() < 0)
					continue;
				
				int index = match.getIndex();
				int length = match.getLength();
				int charge = match.getCharge();
				
				String annotationStr = match.getPepSeq();
				SimpleDBSearchScorer<NominalMass> scorer = specScanner.getSpecKeyScorerMap().get(new SpecKey(specIndex, charge));
				ArrayList<Integer> specIndexList = specScanner.getSpecKey(specIndex, charge).getSpecIndexList();
				if(specIndexList == null)
				{
					specIndexList = new ArrayList<Integer>();
					specIndexList.add(specIndex);
				}
				
				float expMass = scorer.getPrecursorPeak().getMass();
				float theoMass = pep.getParentMass();
				float pmError = Float.MAX_VALUE;
				float peptideMass = expMass - (float)Composition.H2O;
				
				float tolDaRight = specScanner.getRightParentMassTolerance().getToleranceAsDa(peptideMass);
				int nC13 = tolDaRight >= 0.5f ? 0 : specScanner.getNumAllowedC13();
				for(int numC13=0; numC13<=nC13; numC13++)
				{
					float error = expMass-theoMass-(float)(Composition.ISOTOPE)*numC13; 
					if(Math.abs(error) < Math.abs(pmError))
						pmError = error;
				}
				if(specScanner.getRightParentMassTolerance().isTolerancePPM())
					pmError = pmError/theoMass*1e6f;
				
				String protein = sa.getAnnotation(index+1);
				
				int score = match.getScore();
				double specProb = match.getSpecProb();
				int numPeptides = sa.getNumDistinctPeptides(peptideStr.length()+1);
				double pValue = MSGFDBResultGenerator.DBMatch.getPValue(specProb, numPeptides);
				String specProbStr;
				if(specProb < Float.MIN_NORMAL)
					specProbStr = String.valueOf(specProb);
				else
					specProbStr = String.valueOf((float)specProb);
				String pValueStr;
				if(specProb < Float.MIN_NORMAL)
					pValueStr = String.valueOf(pValue);
				else
					pValueStr = String.valueOf((float)pValue);

				if(!replicateMergedResults)
				{
					StringBuffer specIndexStrBuf = new StringBuffer();
					StringBuffer scanNumStrBuf = new StringBuffer();
					StringBuffer actMethodStrBuf = new StringBuffer();
					specIndexStrBuf.append(specIndexList.get(0));
					actMethodStrBuf.append(scorer.getActivationMethodArr()[0]);
					scanNumStrBuf.append(scorer.getScanNumArr()[0]);
					for(int j=1; j<scorer.getActivationMethodArr().length; j++)
					{
						specIndexStrBuf.append("/"+specIndexList.get(j));
						scanNumStrBuf.append("/"+scorer.getScanNumArr()[j]);
						actMethodStrBuf.append("/"+scorer.getActivationMethodArr()[j]);
					}
					
					String resultStr =
						specFileName+"\t"
						+specIndexStrBuf.toString()+"\t"
						+scanNumStrBuf.toString()+"\t"
						+actMethodStrBuf.toString()+"\t" 
						+scorer.getPrecursorPeak().getMz()+"\t"
						+pmError+"\t"
						+match.getCharge()+"\t"
						+annotationStr+"\t"
						+protein+"\t"
						+match.getDeNovoScore()+"\t"
						+score+"\t"
						+specProbStr+"\t"
						+pValueStr;
					MSGFDBResultGenerator.DBMatch dbMatch = new MSGFDBResultGenerator.DBMatch(specProb, numPeptides, resultStr, match.getScoreDist());		
					gen.add(dbMatch);				
				}
				else
				{
					for(int j=0; j<scorer.getActivationMethodArr().length; j++)
					{
						String resultStr =
							specFileName+"\t"
							+specIndexList.get(j)+"\t"
							+scorer.getScanNumArr()[j]+"\t"
							+scorer.getActivationMethodArr()[j]+"\t" 
							+scorer.getPrecursorPeak().getMz()+"\t"
							+pmError+"\t"
							+match.getCharge()+"\t"
							+annotationStr+"\t"
							+protein+"\t"
							+match.getDeNovoScore()+"\t"
							+score+"\t"
							+specProbStr+"\t"
							+pValueStr;
						MSGFDBResultGenerator.DBMatch dbMatch = new MSGFDBResultGenerator.DBMatch(specProb, numPeptides, resultStr, match.getScoreDist());		
						gen.add(dbMatch);				
					}
				}
			}
		}
	}

	private static HashMap<String,Double> modTable;
	private static HashMap<String,String> modResidueTable;
	
	static {
		modTable = new HashMap<String,Double>();
		modTable.put("Pyro-carbamidomethyl", Modification.get("PyroCarbamidomethyl").getAccurateMass());
		modTable.put("Carbamidomethyl", Modification.get("Carbamidomethylation").getAccurateMass());
		modTable.put("Gln->pyro-Glu", Modification.get("PyrogluQ").getAccurateMass());
		modTable.put("Acetyl", Modification.get("Acetylation").getAccurateMass());
		modTable.put("Oxidation", Modification.get("Oxidation").getAccurateMass());
		modTable.put("Glu->pyro-Glu", Modification.get("PyrogluE").getAccurateMass());
		
		modResidueTable = new HashMap<String,String>();
		modResidueTable.put("Pyro-carbamidomethyl", String.format("%.3f", Modification.get("PyroCarbamidomethyl").getMass()));
		modResidueTable.put("Carbamidomethyl", String.format("%.3f", "+"+Modification.get("Carbamidomethylation").getMass()));
		modResidueTable.put("Gln->pyro-Glu", String.format("%.3f", Modification.get("PyrogluQ").getMass()));
		modResidueTable.put("Acetyl", String.format("%.3f", "+"+Modification.get("Acetylation").getMass()));
		modResidueTable.put("Oxidation", String.format("%.3f", "+"+Modification.get("Oxidation").getMass()));
		modResidueTable.put("Glu->pyro-Glu", String.format("%.3f", Modification.get("PyrogluE").getMass()));
		
	}

}