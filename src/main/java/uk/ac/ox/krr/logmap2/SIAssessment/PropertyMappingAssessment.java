/*******************************************************************************
 * Copyright 2012 by the Department of Computer Science (University of Oxford)
 * 
 *    This file is part of LogMap.
 * 
 *    LogMap is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 * 
 *    LogMap is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 * 
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with LogMap.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package uk.ac.ox.krr.logmap2.SIAssessment;

import java.util.Set;

import uk.ac.ox.krr.logmap2.Parameters;
import uk.ac.ox.krr.logmap2.indexing.IndexManager;
import uk.ac.ox.krr.logmap2.mappings.MappingManager;

/**
 * Manages the compatibility of properties
 * 
 * @author Ernesto
 * edits from JD, see `git log`
 */
public abstract class PropertyMappingAssessment<T> {
	
	protected IndexManager index;
	protected MappingManager mapping_manager;
	
	protected final int EMPTY_RANGE_OR_DOMAIN=0;
	protected final int SAME_RANGE_AND_DOMAIN=1;
	protected final int ONLY_SAME_RANGE_OR_DOMAIN=2;
	protected final int COMPATIBLE_RANGE_DOMAIN=3;
	protected final int INCOMPATIBLE_RANGE_OR_DOMAIN=4;
	protected final int PROBABLY_INCOMPATIBLE_RANGE_OR_DOMAIN=5;
	protected final int PERMIT=6;

	protected final int MODE_STRICT=0;
	protected final int MODE_LIGHT=1;
	protected final int MODE_LIBERAL=2;
	protected final int MODE_PERMISSIVE=3;

	/*
	 * Mode matrix (for Parameters.property_compatibility):
	 *  ---------------------------------------------------------------------------------------------------
	 *  Threshold decision                    strict          light           liberal         permissive
	 *  ---------------------------------------------------------------------------------------------------
	 *  all four domain/range sets empty      EMPTY 0.90      EMPTY 0.90      EMPTY 0.90      PERMIT 0.75
	 *  asymmetric empty                      INCOMPAT 2.0    SKIPPED         SKIPPED         SKIPPED
	 *  domain/range equivalent to Top        INCOMPAT 2.0    INCOMPAT 2.0    INCOMPAT 2.0    INCOMPAT 2.0
	 *  disjointness conflict in cross-pairs  INCOMPAT 2.0    INCOMPAT 2.0    INCOMPAT 2.0    INCOMPAT 2.0
	 *  same domain and range                 SAME 0.75       SAME 0.75       SAME 0.75       SAME 0.75
	 *  same one side, other non-conflicting  ONLY_SAME 0.85  ONLY_SAME 0.85  ONLY_SAME 0.85  PERMIT 0.75
	 *  both differ, non-conflicting          PROB.INCOMP 1.5 PROB.INCOMP 1.5 COMPAT 0.90     PERMIT 0.75
	 *  dprop: Literal range wildcard         no              yes             yes             yes
	 *  dprop: datatype mismatch              INCOMPAT 2.0    INCOMPAT 2.0    INCOMPAT 2.0    INCOMPAT 2.0
	 *  ---------------------------------------------------------------------------------------------------
	 *
	 * Note: data properties have no "both differ, non-conflicting" row (different ranges are a datatype mismatch).
	 * Thus, liberal is behaviourally identical to light for data properties.
	 */

	protected abstract int arePropertiesCompatible(int ident1, int ident2);	
	protected abstract int arePropertiesCompatibleLight(int ident1, int ident2);
	protected abstract int arePropertiesLiberallyCompatible(int ident1, int ident2);
	protected abstract int arePropertiesCompatiblePermissive(int ident1, int ident2);

	/**
	 * This defines a minimum confidence to be a mapping accepted
	 * @param ident1
	 * @param ident2
	 * @return
	 */
	public double getConfidence4Compatibility(int ident1, int ident2){

		int compatibility;

		if (Parameters.property_compatibility.equals("strict")) {
			compatibility = arePropertiesCompatible(ident1, ident2);
		} else if (Parameters.property_compatibility.equals("light")) {
			compatibility = arePropertiesCompatibleLight(ident1, ident2);
		} else if (Parameters.property_compatibility.equals("liberal")) {
			compatibility = arePropertiesLiberallyCompatible(ident1, ident2);
		} else if (Parameters.property_compatibility.equals("permissive")) {
			compatibility = arePropertiesCompatiblePermissive(ident1, ident2);
		} else {
			throw new IllegalArgumentException("Unknown property_compatibility mode '" + Parameters.property_compatibility + "' (expected strict|light|liberal|permissive)");
		}

		switch (compatibility) {
	    	case EMPTY_RANGE_OR_DOMAIN: //Both empty
	    		return 0.90; //0.95
	    	case SAME_RANGE_AND_DOMAIN:
	    		return 0.75;
	    	case ONLY_SAME_RANGE_OR_DOMAIN: //And the other non empty and compatible
	    		return 0.85; //0.90
	    	case COMPATIBLE_RANGE_DOMAIN:
	    		return 0.90; //0.93
	    	case PROBABLY_INCOMPATIBLE_RANGE_OR_DOMAIN:
	    		return 1.5;
			case PERMIT:	// note that the anchor creation score floor is 0.75; thus 0.75 is maximally permissive.
				return 0.75;
	    	case INCOMPATIBLE_RANGE_OR_DOMAIN: //(Also indludes the cases where the domain1/range1 is empty and the other domain2/range2 no)
	    		return 2.0; //Max isub score is 1.0
	    	default:
	    		return 2.0; //Max isub score is 1.0
		}
	}
	
	protected boolean haveSameRange(Set<T> range1, Set<T> range2){
		
		if (range1.size()>0 && range2.size()>0){			
			return range1.equals(range2);
		}

		return false;	
	}
	
	
	protected boolean haveSameDomain(Set<Integer> dom1, Set<Integer> dom2){
		
		if (dom1.size()>0 || dom2.size()>0){			
			return dom1.equals(dom2);
		}

		return false;				
		//return intersect.size()>0 && dom1.size()==intersect.size() && dom2.size()==intersect.size();
	}
	
	
}
