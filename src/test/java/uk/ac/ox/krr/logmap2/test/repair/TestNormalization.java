package uk.ac.ox.krr.logmap2.test.repair;

import java.util.Set;

import org.semanticweb.HermiT.structural.OWLNormalizationAdapted;
import org.semanticweb.HermiT.structural.OWLAxiomsAdapted;
import org.semanticweb.HermiT.structural.OWLAxiomsExpressivity;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

import uk.ac.ox.krr.logmap2.OntologyLoader;

public class TestNormalization {
	
	OWLNormalizationAdapted normalization;
	
	
	public TestNormalization() {
		
		//Empty structure to be populated with normalised axioms
		OWLAxiomsAdapted axioms = new OWLAxiomsAdapted();
		
		
		String onto_iri="file:/C:/Users/Ernes/Documents/test_norm.owl";
		
		OntologyLoader loader;
		try {
			loader = new OntologyLoader(onto_iri);
			
			
			normalization = new OWLNormalizationAdapted(OWLManager.getOWLDataFactory(), axioms, 1);
			normalization.processOntology(loader.getOWLOntology());
			
			
			
			
					
			for (OWLClassExpression[] expresionsGCI : axioms.getNormalisedConceptInclusions()) {
				System.out.println("Concept inclusion:");
				//System.out.println("\tSub: " + expresionsGCI[0]);
				//System.out.println("\tSup: " + expresionsGCI[1]);
				for (int i=0; i<expresionsGCI.length; i++)
					System.out.println("\tCexp "+ i + ":" + expresionsGCI[i]);
			}
			
			
			
		} catch (OWLOntologyCreationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		
	}
	
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		new TestNormalization();
	
	}
	
	

}
