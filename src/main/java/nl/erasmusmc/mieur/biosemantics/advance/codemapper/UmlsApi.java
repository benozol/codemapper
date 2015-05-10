package nl.erasmusmc.mieur.biosemantics.advance.codemapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import nl.erasmusmc.mieur.biosemantics.advance.codemapper.umls_ext.ExtCodingSystem;

import org.apache.log4j.Logger;

/**
 * Database based implementation of the UMLS API used for the code mapper.
 *
 * Two SQL indices in MRREL for CUI1 and CUI2 speed up the lookup of hypernyms/hyponyms:
 * CREATE INDEX MRREL_CUI1 ON MRREL (CUI1);
 * CREATE INDEX MRREL_CUI2 ON MRREL (CUI2)
 *
 * @author benus
 *
 */
public class UmlsApi  {

	private Connection connection;
	private String uri;
	private Properties connectionProperties;
	private List<String> codingSystemsWithDefinition;
	private List<String> availableCodingSystems;
	private static Logger logger = Logger.getLogger("AdvanceCodeMapper");

	public UmlsApi(String uri, Properties connectionProperties, List<String> availableCodingSystems, List<String> codingSystemsWithDefinition) {
		this.uri = uri;
		this.connectionProperties = connectionProperties;
		this.availableCodingSystems = availableCodingSystems;
		this.codingSystemsWithDefinition = codingSystemsWithDefinition;
	}

	private Connection getConnection() throws SQLException {
		if (connection == null || connection.isClosed())
			connection = DriverManager.getConnection(uri, connectionProperties);
		return connection;
	}

	private Map<String, ExtCodingSystem> extCodingSystems = new HashMap<>();

	public void registerCodingSystemsExtension(ExtCodingSystem ext) {
		extCodingSystems.put(ext.getCodingSystem().getAbbreviation(), ext);
	}

	public List<CodingSystem> getCodingSystems() throws CodeMapperException {

		List<CodingSystem> codingSystems = new LinkedList<>();
		for (ExtCodingSystem ext: extCodingSystems.values())
			codingSystems.add(ext.getCodingSystem());

		String query = "SELECT DISTINCT rsab, son, sf FROM MRSAB WHERE CURVER = 'Y'";
		try (PreparedStatement statement = getConnection().prepareStatement(query)) {
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				String rsab = result.getString(1);
				String name = result.getString(2);
				String family = result.getString(2);
				if (availableCodingSystems == null || availableCodingSystems.contains(rsab)) {
					CodingSystem codingSystem = new CodingSystem(rsab, name, family);
					codingSystems.add(codingSystem);
				}
			}
			return codingSystems;
		} catch (SQLException e) {
			throw CodeMapperException.server("Cannot execute query for coding systems", e);
		}
	}

	public Map<String, String> getPreferredNames(Collection<String> cuis) throws CodeMapperException {

		if (cuis.isEmpty())
			return new TreeMap<>();
		else {

			String queryFmt =
					"SELECT DISTINCT cui, str FROM MRCONSO "
					+ "WHERE cui in (%s) "
					+ "AND lat = 'ENG' "
					+ "AND ispref = 'Y' "
					+ "AND ts = 'P' "
					+ "AND stt = 'PF'";
			String query = String.format(queryFmt, Utils.sqlPlaceholders(cuis.size()));

			try (PreparedStatement statement = connection.prepareStatement(query)) {

				int offset = 1;
				for (Iterator<String> iter = cuis.iterator(); iter.hasNext(); offset++)
					statement.setString(offset, iter.next());

				ResultSet result = statement.executeQuery();

				Map<String, String> names = new TreeMap<>();
				while (result.next()) {
					String cui = result.getString(1);
					String name = result.getString(2);
					names.put(cui, name);
				}

				Set<String> missings = new TreeSet<>(cuis);
				missings.removeAll(names.keySet());
				for (String missing : missings)
					logger.warn("No preferred name found for CUI " + missing);
				return names;
			} catch (SQLException e) {
				throw CodeMapperException.server("Cannot execute query for preferred names", e);
			}
		}
	}

	public List<UmlsConcept> getCompletions(String q, List<String> codingSystems0, List<String> semanticTypes) throws CodeMapperException {
		if (q.length() < 3) {
			throw CodeMapperException.user("Completions query too short");
		} else {

			Set<String> codingSystems = new HashSet<>();
			for (String abbr: codingSystems0)
				if (extCodingSystems.containsKey(abbr))
					codingSystems.add(extCodingSystems.get(abbr).getCodingSystem().getAbbreviation());
				else
					codingSystems.add(abbr);

			String queryFmt =
					"SELECT DISTINCT m1.cui, m1.str " // Get the distinct MRCONSO.str
					+ "FROM MRCONSO AS m1 "
					+ "INNER JOIN MRCONSO AS m2 "
					+ "INNER JOIN MRSTY AS sty "
					+ "ON m1.cui = m2.cui "
					+ "AND m1.cui = sty.cui "
					+ "WHERE m1.ts = 'P' " // from preferred terms in MRCONSO ...
					+ "AND m1.stt = 'PF' "
					+ "AND m1.ispref = 'Y' "
					+ "AND m1.lat = 'ENG' "
					+ "AND m1.str LIKE ? " // that match the query string
					+ "AND m2.sab IN (%s) " // that are in selected coding systems
					+ "AND sty.tui IN (%s)"; // that have the selected semantic types

			String query = String.format(queryFmt, Utils.sqlPlaceholders(codingSystems.size()), Utils.sqlPlaceholders(semanticTypes.size()));
			try (PreparedStatement statement = getConnection().prepareStatement(query)) {
				int offset = 1;
				statement.setString(offset++, q + "%");
				for (Iterator<String> iter = codingSystems.iterator(); iter.hasNext(); offset++)
					statement.setString(offset, iter.next());
				for (Iterator<String> iter = semanticTypes.iterator(); iter.hasNext(); offset++)
					statement.setString(offset, iter.next());
				ResultSet result = statement.executeQuery();
				List<UmlsConcept> completions = new LinkedList<>();
				while (result.next()) {
					String cui = result.getString(1);
					String str = result.getString(2);
					UmlsConcept concept = new UmlsConcept(cui, str);
					completions.add(concept);
				}
				return completions;
			} catch (SQLException e) {
				throw CodeMapperException.server("Cannot execute query for completions", e);
			}
		}
	}

	private Map<String, List<String>> getSemanticTypes(Collection<String> cuis) throws CodeMapperException {
		if (cuis.isEmpty())
			return new TreeMap<>();
		else {
			String queryFmt =
					"SELECT DISTINCT cui, tui "
					+ "FROM MRSTY "
					+ "WHERE cui in (%s) "
					+ "ORDER BY cui, tui";
			String query = String.format(queryFmt, Utils.sqlPlaceholders(cuis.size()));

			try (PreparedStatement statement = getConnection().prepareStatement(query)) {

				int offset = 1;

				for (Iterator<String> iter = cuis.iterator(); iter.hasNext(); offset++)
					statement.setString(offset, iter.next());

                logger.debug(statement);
				ResultSet result = statement.executeQuery();

				Map<String, List<String>> semanticTypes = new TreeMap<>();
				while (result.next()) {
					String cui = result.getString(1);
					String tui = result.getString(2);
					if (!semanticTypes.containsKey(cui))
						semanticTypes.put(cui, new LinkedList<String>());
					semanticTypes.get(cui).add(tui);
				}
				return semanticTypes;
			} catch (SQLException e) {
				throw CodeMapperException.server("Cannot execute query for semantic types", e);
			}
		}
	}

	public Map<String, List<SourceConcept>> getSourceConcepts(Collection<String> cuis, Collection<String> codingSystems0)
			throws CodeMapperException {

		if (cuis.isEmpty() || codingSystems0 == null)
			return new TreeMap<>();
		else {

			// Translate extended coding systems to normal coding systems
			Set<String> codingSystems = new HashSet<>();
			List<String> extAbbrs = new LinkedList<>();
			for (String abbr: codingSystems0)
				if (extCodingSystems.containsKey(abbr)) {
					extAbbrs.add(abbr);
					codingSystems.add(extCodingSystems.get(abbr).getCodingSystem().getAbbreviation());
				}
				else
					codingSystems.add(abbr);

			String sabPlaceholders = "";
			if (!codingSystems.isEmpty())
				sabPlaceholders = String.format("AND sab IN (%s)", Utils.sqlPlaceholders(codingSystems.size()));

			String queryFmt =
				  	"SELECT DISTINCT cui, sab, code, str, tty "
					+ "FROM MRCONSO "
					+ "WHERE cui IN (%s) %s ORDER BY cui, sab, code, str";
			String query = String.format(queryFmt, Utils.sqlPlaceholders(cuis.size()), sabPlaceholders);

			try (PreparedStatement statement = getConnection().prepareStatement(query)) {

				int offset = 1;

				for (Iterator<String> iter = cuis.iterator(); iter.hasNext(); offset++)
					statement.setString(offset, iter.next());

				if (codingSystems != null)
					for (Iterator<String> iter = codingSystems.iterator(); iter.hasNext(); offset++)
						statement.setString(offset, iter.next());

                logger.debug(statement);
				ResultSet result = statement.executeQuery();

				Map<String, List<SourceConcept>> sourceConcepts = new TreeMap<>();
				String lastCui = null, lastSab = null, lastCode = null;
				SourceConcept currentSourceConcept = null;
				while (result.next()) {
					String cui = result.getString(1);
					String sab = result.getString(2);
					String code = result.getString(3);
					String str = result.getString(4);
					String tty = result.getString(5);
					if (!cui.equals(lastCui) || !sab.equals(lastSab) || !code.equals(lastCode)) {
						currentSourceConcept = new SourceConcept();
						currentSourceConcept.setCui(cui);
						currentSourceConcept.setCodingSystem(sab);
						currentSourceConcept.setId(code);
						currentSourceConcept.setPreferredTerm(str);
						if (!sourceConcepts.containsKey(cui))
							sourceConcepts.put(cui, new LinkedList<SourceConcept>());
						sourceConcepts.get(cui).add(currentSourceConcept);
					}
					if ("PT".equals(tty))
						currentSourceConcept.setPreferredTerm(str);
					lastCui = cui;
					lastSab = sab;
					lastCode = code;
				}

				// Create extended source codes
				for (String extAbbr: extAbbrs) {

					ExtCodingSystem extCodingSystem = extCodingSystems.get(extAbbr);

					Map<String, List<SourceConcept>> referenceSourceConcepts = new HashMap<>();
					for (String cui: sourceConcepts.keySet()) {
						referenceSourceConcepts.put(cui, new LinkedList<SourceConcept>());
						for (SourceConcept sourceConcept: sourceConcepts.get(cui))
							if (extCodingSystem.getReferenceCodingSystem()
									.equals(sourceConcept.getCodingSystem()))
								referenceSourceConcepts.get(cui).add(sourceConcept);
					}

					Map<String, Map<String, List<SourceConcept>>> extSourceConcepts =
							extCodingSystem.mapCodes(referenceSourceConcepts);

					for (String cui: sourceConcepts.keySet()) {
						Set<SourceConcept> extSourceConceptsForCui = new HashSet<>();
						for (List<SourceConcept> extSourceConceptForCui: extSourceConcepts.get(cui).values())
							extSourceConceptsForCui.addAll(extSourceConceptForCui);
						sourceConcepts.get(cui).addAll(extSourceConceptsForCui);
					}
				}

				Set<String> missings = new TreeSet<>(cuis);
				missings.removeAll(sourceConcepts.keySet());
				for (String missing : missings)
					logger.warn("No UMLS concept found for CUI " + missing);
				return sourceConcepts;
			} catch (SQLException e) {
				throw CodeMapperException.server("Cannot execute query for source concepts", e);
			}
		}
	}

	public Map<String, List<UmlsConcept>> getHyponymsOrHypernyms(List<String> cuis, List<String> codingSystems, boolean hyponymsNotHypernyms) throws CodeMapperException {

		if (cuis.isEmpty())
			return new TreeMap<>();
		else {

			String queryFmt =
					   "SELECT DISTINCT %s "
					+ "FROM MRREL "
					+ "WHERE rel in ('RN', 'CHD') "
					+ "AND %s IN (%s) "
					+ "AND cui1 != cui2 "
					+ "AND (rela IS NULL OR rela = 'isa')";
			String selection = hyponymsNotHypernyms ? "cui1, cui2" : "cui2, cui1";
			String selector = hyponymsNotHypernyms ? "cui1" : "cui2";
			String query = String.format(queryFmt, selection, selector, Utils.sqlPlaceholders(cuis.size()));

			try (PreparedStatement statement = getConnection().prepareStatement(query)) {

				int offset = 1;
				for (int ix = 0; ix < cuis.size(); ix++, offset++)
					statement.setString(offset, cuis.get(ix));

				logger.debug(statement);
				ResultSet result = statement.executeQuery();

				Map<String, Set<String>> related = new TreeMap<>();
				while (result.next()) {
					String cui = result.getString(1);
					String relatedCui = result.getString(2);
					if (!related.containsKey(cui))
						related.put(cui, new TreeSet<String>());
					related.get(cui).add(relatedCui);
				}

				Set<String> relatedCuis = new TreeSet<>();
				for (Collection<String> cs : related.values())
					relatedCuis.addAll(cs);

				Map<String, UmlsConcept> relatedConcepts = getConcepts(relatedCuis, codingSystems);

				Map<String, List<UmlsConcept>> relatedByReference = new TreeMap<>();
				for (String cui: cuis) {
					List<UmlsConcept> concepts = new LinkedList<>();
					if (related.containsKey(cui))
						for (String relatedCui: related.get(cui))
							if (relatedConcepts.containsKey(relatedCui))
								concepts.add(relatedConcepts.get(relatedCui));
					relatedByReference.put(cui, concepts);
				}
				return relatedByReference;
			} catch (SQLException e) {
				throw CodeMapperException.server("Cannot execute query for related concepts", e);
			}
		}
	}

	private Map<String, String> getDefinitions(Collection<String> cuis) throws CodeMapperException {

		if (cuis.isEmpty())
			return new TreeMap<>();
		else {

			String queryFmt = "SELECT DISTINCT cui, sab, def FROM MRDEF WHERE cui IN (%s)";
			String query = String.format(queryFmt, Utils.sqlPlaceholders(cuis.size()));

			try (PreparedStatement statement = getConnection().prepareStatement(query)) {

				int offset = 1;
				for (Iterator<String> iter = cuis.iterator(); iter.hasNext(); offset++)
					statement.setString(offset, iter.next());

				logger.debug(statement);
				ResultSet result = statement.executeQuery();

				Map<String, Map<String, String>> definitionsByVocabularies = new TreeMap<>();
				while (result.next()) {
					String cui = result.getString(1);
					String sab = result.getString(2);
					String def = result.getString(3);
					if (!definitionsByVocabularies.containsKey(cui))
						definitionsByVocabularies.put(cui, new TreeMap<String, String>());
					definitionsByVocabularies.get(cui).put(sab, def);
				}

				Map<String, String> definitions = new TreeMap<>();
				for (String cui : cuis)
					if (!definitionsByVocabularies.containsKey(cui))
						definitions.put(cui, "");
					else
						for (String voc : codingSystemsWithDefinition)
							if (definitionsByVocabularies.get(cui).containsKey(voc)) {
								definitions.put(cui, definitionsByVocabularies.get(cui).get(voc));
								break;
							}

				return definitions;
			} catch (SQLException e) {
				throw CodeMapperException.server("Cannot execute query for definitions", e);
			}
		}
	}

	public Map<String, UmlsConcept> getConcepts(Collection<String> cuis, Collection<String> codingSystems)
			throws CodeMapperException {
		if (cuis.isEmpty())
			return new TreeMap<>();
		else {

			cuis = new LinkedList<>(new TreeSet<>(cuis)); // unique CUIs

	        Map<String, List<SourceConcept>> sourceConcepts = getSourceConcepts(cuis, codingSystems);
	        Map<String, String> preferredNames = getPreferredNames(cuis);
	        Map<String, String> definitions = getDefinitions(cuis);
	        Map<String, List<String>> semanticTypes = getSemanticTypes(cuis);

	        Map<String, UmlsConcept> concepts = new TreeMap<>();
	        for (String cui : cuis) {
	        	UmlsConcept concept = new UmlsConcept();
	        	concept.setCui(cui);
	        	concept.setDefinition(definitions.get(cui));
	        	concept.setPreferredName(preferredNames.get(cui));
	            if (sourceConcepts.containsKey(cui))
	            	concept.setSourceConcepts(sourceConcepts.get(cui));
	            if (semanticTypes.containsKey(cui))
	            	concept.setSemanticTypes(semanticTypes.get(cui));
	            concepts.put(cui, concept);
	        }
	        logger.debug("Found source concepts " + concepts.size());

	        return concepts;
		}
	}
}
