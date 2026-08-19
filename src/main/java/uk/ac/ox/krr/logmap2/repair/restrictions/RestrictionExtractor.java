package uk.ac.ox.krr.logmap2.repair.restrictions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.semanticweb.HermiT.structural.OWLAxiomsAdapted;
import org.semanticweb.HermiT.structural.OWLNormalizationAdapted;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import uk.ac.ox.krr.logmap2.io.LogOutput;

/**
 * Minimal restriction extraction; uses the HermiT-derived noramliser: OWLNormalizationAdapted.
 * The normaliser rewrites every axiom into clauses (arrays of disjunctions) and replaces
 * every complex filler with a fresh named class: internal:def#N (per its sound direction).
 * After normalisation every restriction we keep has a NAMED filler (real class, def#N, 
 * Thing or Nothing); per the triple view:
 *
 *     [R, some, C]    [R, only, C]    [R, min_n, C]    [R, max_n, C]
 *
 * Decoding rule for one clause: negated literals form the left-hand-side conjunction,
 * positive literals the right-hand-side disjunction. We keep the Horn-shaped clauses
 * and sort them into five tables:
 *
 *   atoms          the interned restriction triples
 * 
 *   attachments    A1 ^..^ Ak -> atom              (empty body = TOP -> atom: ranges, functionality)
 * 
 *   incomings      atom (^ A1..) -> B | BOTTOM     (restrictions used on the subclass side; the normaliser 
 * 												     emits these as [B, ..., all(R, not X)]; domains are
 *                                                   the special case all(R, Nothing))
 * 
 *   defEdges       plain class edges mentioning a def#N class (definitional wiring; edges over real classes 
 * 					only are stock LogMap's job -- counted)
 * 
 *   propertyFacts  func(R), range(R, C), subProp(P, Q), subDataProp(p, q)
 *
 * Everything that does not fit a pattern is counted and kept in 'skipped' with a reason; nothing is dropped silently.
 */
public class RestrictionExtractor {

	// Off-switch for the OntologyProcessing hook (experiment convenience)
	public static boolean ACTIVE = true;

	// One interned restriction triple: [property, kind, filler].
	public static class Atom {
		public final String property;  // "inv(p)" for an inverse property expression
		public final String kind;      // some | only | min_n | max_n | self | d_some | ...
		public final String filler;    // named class, def#N, Thing, Nothing, data range

		Atom(String property, String kind, String filler) {
			this.property = property;
			this.kind = kind;
			this.filler = filler;
		}
		public String toString() {
			return "[" + property + ", " + kind + ", " + filler + "]";
		}
	}

	// body1 ^ .. ^ bodyK -> atom. An empty body reads as TOP -> atom.
	public static class Attachment {
		public final List<String> body;
		public final Atom atom;

		Attachment(List<String> body, Atom atom) {
			this.body = body;
			this.atom = atom;
		}
		public String toString() {
			return (body.isEmpty() ? "TOP" : join(body, " ^ ")) + " -> " + atom;
		}
	}

	// atom ^ extraBody -> head, where head may be "BOTTOM".
	public static class Incoming {
		public final Atom atom;
		public final List<String> extraBody;
		public final String head;

		Incoming(Atom atom, List<String> extraBody, String head) {
			this.atom = atom;
			this.extraBody = extraBody;
			this.head = head;
		}
		public String toString() {
			return atom + (extraBody.isEmpty() ? "" : " ^ " + join(extraBody, " ^ ")) + " -> " + head;
		}
	}


	private final Map<String, Atom> atoms = new LinkedHashMap<String, Atom>();
	private final List<Attachment> attachments = new ArrayList<Attachment>();
	private final List<Incoming> incomings = new ArrayList<Incoming>();
	private final List<String> defEdges = new ArrayList<String>();
	private final List<String> propertyFacts = new ArrayList<String>();
	private final List<String> skipped = new ArrayList<String>();
	private int already_propositional = 0;  //named-only Horn clauses: stock LogMap's job


	/**
	 * RestrictionExtractor FACTORY.
	 * 
	 * Normalises one ontology and extracts the tables. Call once per ontology, while
	 * the OWLOntology object is alive (i.e. inside setTaxonomicData).
	 *
	 * @param firstFreshId offset for the internal:def#N names; give each ontology of a
	 *        pair a disjoint range (e.g. 1 and 100000) so fresh names never collide.
	 */
	public static RestrictionExtractor extract(OWLOntology onto, int firstFreshId) {

		OWLAxiomsAdapted normalised = new OWLAxiomsAdapted();
		new OWLNormalizationAdapted(OWLManager.getOWLDataFactory(), normalised, firstFreshId).processOntology(onto);

		RestrictionExtractor extractor = new RestrictionExtractor();
		for (OWLClassExpression[] clause : normalised.getNormalisedConceptInclusions())
			extractor.decodeClause(clause);

		// Object-property hierarchy comes out of the normaliser already flattened
		// (read via the OWLAxiomsAdapted getter: the raw HermiT 1.3.8 fields are not
		// public; the data-property counterpart can get the same treatment when needed)

		for (OWLObjectPropertyExpression[] inc : normalised.getNormalisedObjectPropertyInclusions())
			extractor.propertyFacts.add("subProp(" + render(inc[0]) + ", " + render(inc[1]) + ")");

		return extractor;
	}


	private void decodeClause(OWLClassExpression[] clause) {

		List<String> body = new ArrayList<String>();
		List<String> headsNamed = new ArrayList<String>();
		List<OWLClassExpression> headsRestr = new ArrayList<OWLClassExpression>();

		for (OWLClassExpression literal : clause) {
			if (literal instanceof OWLObjectComplementOf) {
				OWLClassExpression operand = ((OWLObjectComplementOf) literal).getOperand();
				if (operand instanceof OWLClass)
					body.add(render(operand));
				else {
					skip(clause, "negated non-atomic literal");
					return;
				}
			}
			else if (literal instanceof OWLClass)
				headsNamed.add(render(literal));
			else if (isRestriction(literal))
				headsRestr.add(literal);
			else {
				skip(clause, "unhandled literal kind " + literal.getClassExpressionType());
				return;
			}
		}

		// CASE 1: no restriction involved -- plain propositional clause.

		if (headsRestr.isEmpty()) {
			if (headsNamed.size() > 1) {
				skip(clause, "disjunctive head over named classes"); // e.g. def#N -> A or B
				return;
			}
			if (mentionsDef(body) || mentionsDef(headsNamed))
				defEdges.add((body.isEmpty() ? "TOP" : join(body, " ^ ")) + " -> " + (headsNamed.isEmpty() ? "BOTTOM" : headsNamed.get(0)));
			else
				already_propositional++;
			return;
		}

		// A clause with two restriction literals is a genuine disjunction: not Horn.

		if (headsRestr.size() > 1) {
			skip(clause, "two restriction literals (disjunction)");
			return;
		}

		OWLClassExpression restriction = headsRestr.get(0);

		// CASE 2a: all(R, not X) -- the normaliser's encoding of a restriction that was used on the SUBCLASS side:  
		// [B..., notA..., all(R, not X)]  says  A ^ some(R, X) -> B.  
		// Domains are the sub-case all(R, Nothing):  [B, all(R, Nothing)]  says  some(R, Thing) -> B.

		if (restriction instanceof OWLObjectAllValuesFrom) {
			
			OWLObjectAllValuesFrom all = (OWLObjectAllValuesFrom) restriction;
			OWLClassExpression filler = all.getFiller();
			String someFiller = null;
			
			if (filler instanceof OWLObjectComplementOf && ((OWLObjectComplementOf) filler).getOperand() instanceof OWLClass)
				someFiller = render(((OWLObjectComplementOf) filler).getOperand());
			else if (filler.isOWLNothing())
				someFiller = "Thing";
			
			if (someFiller != null) {
				if (headsNamed.size() > 1) {
					skip(clause, "disjunctive head");
					return;
				}
				Atom atom = intern(render(all.getProperty()), "some", someFiller);
				incomings.add(new Incoming(atom, body, headsNamed.isEmpty() ? "BOTTOM" : headsNamed.get(0)));
				return;
			}
			// else: a plain universal; falls through to the attachment case
		}

		// CASE 2b: plain-filler restriction:  [notA1.., RESTR]  says  A1 ^..^ Ak -> RESTR.
		// An empty body reads TOP -> RESTR (that shape IS a range / functionality fact).

		if (!headsNamed.isEmpty()) {
			skip(clause, "restriction beside a named head (disjunction)");
			return;
		}
		Atom atom = internRestriction(restriction);
		if (atom == null) {
			skip(clause, "filler not atomic after normalisation");
			return;
		}
		attachments.add(new Attachment(body, atom));
		if (body.isEmpty()) {
			if (atom.kind.equals("max_1") && atom.filler.equals("Thing"))
				propertyFacts.add("func(" + atom.property + ")");
			else if (atom.kind.equals("only"))
				propertyFacts.add("range(" + atom.property + ", " + atom.filler + ")");
		}
	}


	// Interns the triple for a positively-occurring restriction with a named filler; 
	// null if the shape is outside the minimal fragment.

	private Atom internRestriction(OWLClassExpression restriction) {
		if (restriction instanceof OWLObjectSomeValuesFrom) {
			OWLObjectSomeValuesFrom r = (OWLObjectSomeValuesFrom) restriction;
			return namedFillerAtom(render(r.getProperty()), "some", r.getFiller());
		}
		if (restriction instanceof OWLObjectAllValuesFrom) {
			OWLObjectAllValuesFrom r = (OWLObjectAllValuesFrom) restriction;
			return namedFillerAtom(render(r.getProperty()), "only", r.getFiller());
		}
		if (restriction instanceof OWLObjectMinCardinality) {
			OWLObjectMinCardinality r = (OWLObjectMinCardinality) restriction;
			return namedFillerAtom(render(r.getProperty()), "min_" + r.getCardinality(), r.getFiller());
		}
		if (restriction instanceof OWLObjectMaxCardinality) {
			OWLObjectMaxCardinality r = (OWLObjectMaxCardinality) restriction;
			return namedFillerAtom(render(r.getProperty()), "max_" + r.getCardinality(), r.getFiller());
		}
		if (restriction instanceof OWLObjectExactCardinality) {
			//The normaliser splits exact into min+max; kept for robustness only
			OWLObjectExactCardinality r = (OWLObjectExactCardinality) restriction;
			return namedFillerAtom(render(r.getProperty()), "exact_" + r.getCardinality(), r.getFiller());
		}
		if (restriction instanceof OWLObjectHasSelf)
			return intern(render(((OWLObjectHasSelf) restriction).getProperty()), "self", "-");
		//Data restrictions: filler is a data range, rendered as text (no def# naming)
		if (restriction instanceof OWLDataSomeValuesFrom) {
			OWLDataSomeValuesFrom r = (OWLDataSomeValuesFrom) restriction;
			return intern(renderData(r.getProperty()), "d_some", renderRange(r.getFiller()));
		}
		if (restriction instanceof OWLDataAllValuesFrom) {
			OWLDataAllValuesFrom r = (OWLDataAllValuesFrom) restriction;
			return intern(renderData(r.getProperty()), "d_only", renderRange(r.getFiller()));
		}
		if (restriction instanceof OWLDataMinCardinality) {
			OWLDataMinCardinality r = (OWLDataMinCardinality) restriction;
			return intern(renderData(r.getProperty()), "d_min_" + r.getCardinality(), renderRange(r.getFiller()));
		}
		if (restriction instanceof OWLDataMaxCardinality) {
			OWLDataMaxCardinality r = (OWLDataMaxCardinality) restriction;
			return intern(renderData(r.getProperty()), "d_max_" + r.getCardinality(), renderRange(r.getFiller()));
		}
		return null;
	}

	private Atom namedFillerAtom(String property, String kind, OWLClassExpression filler) {
		if (!(filler instanceof OWLClass))
			return null; //complex filler survived: outside the fragment (counted by caller)
		return intern(property, kind, render(filler));
	}

	private Atom intern(String property, String kind, String filler) {
		String key = property + "|" + kind + "|" + filler;
		Atom atom = atoms.get(key);
		if (atom == null) {
			atom = new Atom(property, kind, filler);
			atoms.put(key, atom);
		}
		return atom;
	}

	private static boolean isRestriction(OWLClassExpression expr) {
		return expr instanceof OWLObjectSomeValuesFrom || expr instanceof OWLObjectAllValuesFrom
			|| expr instanceof OWLObjectMinCardinality || expr instanceof OWLObjectMaxCardinality
			|| expr instanceof OWLObjectExactCardinality || expr instanceof OWLObjectHasSelf
			|| expr instanceof OWLDataSomeValuesFrom || expr instanceof OWLDataAllValuesFrom
			|| expr instanceof OWLDataMinCardinality || expr instanceof OWLDataMaxCardinality;
	}


	//---- rendering (short, human-first; fragments are unambiguous on Conference) ----

	private static String render(OWLClassExpression named) {
		OWLClass cls = (OWLClass) named;
		if (cls.isOWLThing()) return "Thing";
		if (cls.isOWLNothing()) return "Nothing";
		String iri = cls.getIRI().toString();
		if (iri.startsWith("internal:def#"))
			return "def#" + iri.substring("internal:def#".length());
		return shortForm(iri);
	}

	private static String render(OWLObjectPropertyExpression property) {
		if (property.isAnonymous())
			return "inv(" + shortForm(((OWLObjectInverseOf) property).getInverse().asOWLObjectProperty().getIRI().toString()) + ")";
		return shortForm(property.asOWLObjectProperty().getIRI().toString());
	}

	private static String renderData(OWLDataPropertyExpression property) {
		return shortForm(property.asOWLDataProperty().getIRI().toString());
	}

	private static String renderRange(OWLDataRange range) {
		if (range instanceof OWLDatatype)
			return shortForm(((OWLDatatype) range).getIRI().toString());
		return range.toString(); //facets etc.: verbatim is fine at this stage
	}

	private static String shortForm(String iri) {
		int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
		return (cut >= 0 && cut < iri.length() - 1) ? iri.substring(cut + 1) : iri;
	}

	private static boolean mentionsDef(List<String> names) {
		for (String name : names)
			if (name.startsWith("def#"))
				return true;
		return false;
	}

	private static String join(List<String> parts, String separator) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.size(); i++)
			sb.append(i == 0 ? "" : separator).append(parts.get(i));
		return sb.toString();
	}

	private void skip(OWLClassExpression[] clause, String reason) {
		StringBuilder sb = new StringBuilder(reason).append(" :: clause{");
		for (int i = 0; i < clause.length; i++)
			sb.append(i == 0 ? "" : " OR ").append(clause[i]);
		skipped.add(sb.append("}").toString());
	}


	//---- access for the (future) repair step, and human output ----

	public Map<String, Atom> getAtoms() { return atoms; }
	public List<Attachment> getAttachments() { return attachments; }
	public List<Incoming> getIncomings() { return incomings; }
	public List<String> getDefEdges() { return defEdges; }
	public List<String> getPropertyFacts() { return propertyFacts; }
	public List<String> getSkipped() { return skipped; }

	public void printSummary(String tag) {
		LogOutput.printAlways("Restriction extraction [" + tag + "]: "
				+ atoms.size() + " atoms, " + attachments.size() + " attachments, "
				+ incomings.size() + " incoming, " + defEdges.size() + " def-edges, "
				+ propertyFacts.size() + " property facts; "
				+ already_propositional + " named-only clauses left to stock LogMap, "
				+ skipped.size() + " skipped");
	}

	public void dump() {
		System.out.println("== atoms (" + atoms.size() + ") ==");
		for (Atom atom : atoms.values()) System.out.println("  " + atom);
		System.out.println("== attachments (" + attachments.size() + ") ==");
		for (Attachment attachment : attachments) System.out.println("  " + attachment);
		System.out.println("== incoming (" + incomings.size() + ") ==");
		for (Incoming incoming : incomings) System.out.println("  " + incoming);
		System.out.println("== def edges (" + defEdges.size() + ") ==");
		for (String edge : defEdges) System.out.println("  " + edge);
		System.out.println("== property facts (" + propertyFacts.size() + ") ==");
		for (String fact : propertyFacts) System.out.println("  " + fact);
		System.out.println("== skipped (" + skipped.size() + ") ==");
		for (String entry : skipped) System.out.println("  " + entry);
		System.out.println("== named-only clauses handled by stock LogMap: " + already_propositional + " ==");
	}
}
