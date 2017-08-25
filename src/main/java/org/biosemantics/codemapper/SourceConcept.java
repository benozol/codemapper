/*******************************************************************************
 * Copyright 2017 Erasmus Medical Center, Department of Medical Informatics.
 * 
 * This program shall be referenced as “Codemapper”.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.biosemantics.codemapper;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class SourceConcept {

    private String cui = null;
    private String codingSystem = null;
    private String id = null;
    private String preferredTerm = null;

    public SourceConcept() {
    }

    public SourceConcept(String cui, String codingSystem, String id, String preferredTerm) {
        this.cui = cui;
        this.codingSystem = codingSystem;
        this.id = id;
        this.preferredTerm = preferredTerm;
    }

    @Override
    public String toString() {
    	return String.format("%s@%s", id, codingSystem);
    }

    public SourceConcept(String cui, String codingSystem, String id) {
        this.cui = cui;
        this.codingSystem = codingSystem;
        this.id = id;
    }

    public String getCodingSystem() {
        return codingSystem;
    }

    public void setCodingSystem(String codingSystem) {
        this.codingSystem = codingSystem;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCui() {
        return cui;
    }

    public void setCui(String cui) {
        this.cui = cui;
    }

    public String getPreferredTerm() {
        return preferredTerm;
    }

	public void setPreferredTerm(String preferredTerm) {
        this.preferredTerm = preferredTerm;
    }

    @Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((codingSystem == null) ? 0 : codingSystem.hashCode());
		result = prime * result + ((cui == null) ? 0 : cui.hashCode());
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((preferredTerm == null) ? 0 : preferredTerm.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SourceConcept other = (SourceConcept) obj;
		if (codingSystem == null) {
			if (other.codingSystem != null)
				return false;
		} else if (!codingSystem.equals(other.codingSystem))
			return false;
		if (cui == null) {
			if (other.cui != null)
				return false;
		} else if (!cui.equals(other.cui))
			return false;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (preferredTerm == null) {
			if (other.preferredTerm != null)
				return false;
		} else if (!preferredTerm.equals(other.preferredTerm))
			return false;
		return true;
	}
}
