/*
 * Copyright 2014 The Kuali Foundation
 * 
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.opensource.org/licenses/ecl2.php
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ua.utility.kfsdbupgrade;

import static com.google.common.io.ByteSource.wrap;
import static com.google.common.io.Files.asByteSource;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.io.ByteSource;

public class MaintainableXMLConversionServiceImpl implements MaintainableXmlConversionService {
	private static final Logger LOGGER = Logger.getLogger(MaintainableXMLConversionServiceImpl.class);
	private static final String SERIALIZATION_ATTRIBUTE = "serialization";
	private static final String CLASS_ATTRIBUTE = "class";
	private static final String MAINTENANCE_ACTION_ELEMENT_NAME = "maintenanceAction";
    private static final String OLD_MAINTAINABLE_OBJECT_ELEMENT_NAME = "oldMaintainableObject";
    private static final String NEW_MAINTAINABLE_OBJECT_ELEMENT_NAME = "newMaintainableObject";

    private static final String ECRD_ROOT_NODE_NAME = "org.kuali.kfs.module.ec.businessobject.EffortCertificationReportDefinition";
    private static final String ECRD_CHILD_NODE_NAME = "effortCertificationReportPositions";
    private static final String LIST_PROXY_NAME = "org.apache.ojb.broker.core.proxy.ListProxyDefaultImpl";

   private LoadingCache<PropertyClassKey, Optional<Class<?>>> propertyClassCache = CacheBuilder.newBuilder().build(new PropertyClassLoader());
	/**
	 * Populated by the <code>pattern</code> elements in the <code>rule</code>
	 * named <code>maint_doc_classname_changes</code> in {@link #rulesXmlFile}.
	 * See the {@link #rulesXmlFile} for more detail.
	 */
    private Map<String, String> classNameRuleMap;
	/**
	 * Populated by the <code>pattern</code> elements in the <code>rule</code>
	 * named <code>maint_doc_changed_class_properties</code> in
	 * {@link #rulesXmlFile}. {@link #setupConfigurationMaps()} also
	 * pre-populates with some values that are applicable to all BOs. See the
	 * {@link #rulesXmlFile} for more detail.
	 */
	private Map<String, Map<String, String>> xPathTransformationRulesMap;

    /**
     * Populated by the <code>pattern</code> elements in the <code>rule</code>
     * named <code>xpath_specific_changes</code> in
     * {@link #rulesXmlFile}. {@link #setupConfigurationMaps()} ]
     */
    private Map<String, Map<String, String>> classPropertyRuleMap;

	/**
	 * Populated by the <code>pattern</code> elements in the <code>rule</code>
	 * named <code>maint_doc_date_changes</code> in {@link #rulesXmlFile}. See
	 * the {@link #rulesXmlFile} for more detail.
	 */
    private Map<String, String> dateRuleMap;
	/**
	 * {@link File} containing the rule maps that will be used to transform the
	 * maintainable document XML.
	 */
	private final ByteSource rulesXmlFile;
	/**
	 * {@link Set} of {@link String}s representing classnames to ignore during
	 * transformation. Values are hardcoded and passed in during construction.
	 */
    private Set<String> ignoreClassSet = new HashSet<String>();
	/**
	 * If a {@link String} classname that begins with <code>edu.arizona</code>
	 * or <code>com.rsmart</code> is encountered in {@link #isValidClass(Class)}
	 * , that classname will be added to this {@link Set}. If it's the first
	 * time such a class has been encountered, it will be logged that that class
	 * is being skipped by processing.
	 */
    private Set <String> uaMaintenanceDocClasses = new HashSet <String>();
    
   public MaintainableXMLConversionServiceImpl(File rulesXmlFile) throws Exception {
     this(wrap(asByteSource(rulesXmlFile).read()));
   }
  /**
   * Constructor
   * 
   * @param rulesXmlFile
   *            Value for {@link #rulesXmlFile}
   * @throws Exception
   */
  public MaintainableXMLConversionServiceImpl(ByteSource rulesXmlFile) throws Exception {
        this.rulesXmlFile = rulesXmlFile;
		setRuleMaps();
        
        ignoreClassSet.add("org.kuali.rice.kim.api.identity.Person");
        ignoreClassSet.add("org.kuali.rice.krad.bo.PersistableBusinessObjectExtension");
        ignoreClassSet.add("org.kuali.rice.core.api.util.type.KualiInteger");
        ignoreClassSet.add("org.kuali.rice.core.api.util.type.KualiPercent");
     
        // these 2 classes try to call Spring services in the default constructor which causes problems. 
        // in this context (no spring). The classes are relatively simple so bypassing them should not be an issue
        ignoreClassSet.add("org.kuali.kfs.coa.businessobject.IndirectCostRecoveryRate");
        ignoreClassSet.add("org.kuali.kfs.module.purap.businessobject.PurchaseOrderContractLanguage");

		// ignore the builtin xml #text
		ignoreClassSet.add("#text");
     }

	public MaintainableXMLConversionServiceImpl(File rulesXmlFile, Level logLevel) throws Exception {
		this(rulesXmlFile);
		LOGGER.setLevel(logLevel);
	}

	/**
	 * Transforms the given <code>xml</code> that is in KFS3 format to KFS6
	 * format.
	 * 
	 * @param xml
	 *            {@link String} of the XML to transform
	 * @return {@link String} of the transformed XML
	 * @throws Exception
	 *             Any {@link Exception}s encountered will be rethrown
	 */
	public String transformMaintainableXML(String xml, String docid) throws Exception {
		/*
		 * a handful of documents have unfriendly Unicode characters which the
		 * XML processor (and the rest of KFS) can't handle. Pre-process to
		 * replace with a friendly base ASCII characters.
		 */
		xml = xml.replace("\u0001", "-");
		xml = xml.replace("\u001e", " ");
		xml = xml.replace("\u001d", " ");
	    String beginning = StringUtils.substringBefore(xml, "<" + OLD_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">");
        String oldMaintainableObjectXML = StringUtils.substringBetween(xml, "<" + OLD_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">", "</" + OLD_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">");
        String newMaintainableObjectXML = StringUtils.substringBetween(xml, "<" + NEW_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">", "</" + NEW_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">");
        String ending = StringUtils.substringAfter(xml, "</" + NEW_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">");

		// quick hack to catch top-level class replacements
		for (String className : classNameRuleMap.keySet()) {
			if (beginning.contains("maintainableImplClass=\"" + className + "\"")) {
				LOGGER.trace("Replacing top-level maintainableImplClass attribute: " + className + " with: "
						+ classNameRuleMap.get(className));
				beginning = beginning.replace("maintainableImplClass=\"" + className + "\"",
					"maintainableImplClass=\"" + classNameRuleMap.get(className) + "\"");
			}
		}
        String convertedOldMaintainableObjectXML = transformSection(oldMaintainableObjectXML, docid);
        String convertedNewMaintainableObjectXML = transformSection(newMaintainableObjectXML, docid);

        String convertedXML =  beginning +
            "<" + OLD_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">" + convertedOldMaintainableObjectXML +  "</" + OLD_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">" +
            "<" + NEW_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">" + convertedNewMaintainableObjectXML +  "</" + NEW_MAINTAINABLE_OBJECT_ELEMENT_NAME + ">" +
            ending;
        return convertedXML;
	}

	/**
	 * Transforms the given <code>xml</code> section from KFS3 format to KFS6
	 * format.
	 * 
	 * @param xml
	 *            {@link String} of the XML to transform
	 * @return {@link String} of the transformed XML
	 * @throws Exception
	 *             Any {@link Exception}s encountered will be rethrown.
	 */
    private String transformSection(String xml, String docid) throws Exception {
    	String rawXml = xml;
        String maintenanceAction = StringUtils.substringBetween(xml, "<" + MAINTENANCE_ACTION_ELEMENT_NAME + ">", "</" + MAINTENANCE_ACTION_ELEMENT_NAME + ">");
        xml = StringUtils.substringBefore(xml, "<" + MAINTENANCE_ACTION_ELEMENT_NAME + ">");

        xml = upgradeBONotes(xml,docid);
        
        if (classNameRuleMap == null) {
            setRuleMaps();
        }

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document document;
		try {
			document = db.parse(new InputSource(new StringReader(xml)));
		} catch (SAXParseException ex) {
			String eol = System.getProperty("line.separator");
			String exMsg = "Failed in db.parse(new InputSource(new StringReader(xml))) where xml=" + xml + eol + 
					       "of maintenanceAction = " +  maintenanceAction + eol +
					       "contained in rawXml = " +  rawXml;									
			throw new SAXParseException(exMsg, null, ex);
		}

        for(Node childNode = document.getFirstChild(); childNode != null;) {
			Node nextChild = childNode.getNextSibling();
            transformClassNode(document, childNode);
            // Also Transform first level Children which have class attribute
            NodeList children = childNode.getChildNodes();        
            for (int n = 0; n < children.getLength(); n++) {
            	Node child = children.item(n);
                if ((child != null) && (child.getNodeType() == Node.ELEMENT_NODE) && (child.hasAttributes())) {
                    NamedNodeMap childAttributes = child.getAttributes();
                    if (childAttributes.item(0).getNodeName() == "class") {
                        String childClassName = childAttributes.item(0).getNodeValue();
        	            if(classPropertyRuleMap.containsKey(childClassName)) {
        	            	Map<String, String> propertyMappings = classPropertyRuleMap.get(childClassName);
        	            	NodeList nestedChildren = child.getChildNodes();            	     
        	                for (int i = 0; i < nestedChildren.getLength()-1; i++) {
        	                	Node property = nestedChildren.item(i);
        	                	String propertyName = property.getNodeName();
        	                	if ((property.getNodeType() == Node.ELEMENT_NODE) && (propertyMappings != null) && (propertyMappings.containsKey(propertyName))) {
    	            				String newPropertyName = propertyMappings.get(propertyName);
    	            				if(StringUtils.isNotBlank(newPropertyName)) {
    	            					document.renameNode(property, null, newPropertyName);
    	            					propertyName = newPropertyName;
    	            				} else {
    	            					// If there is no replacement name then the element needs to be removed
    	            					child.removeChild(property);
    	            				}           	                		           	                		
        	                    }
        	                }
        	            }
                    }                 
                }				
            }
            childNode = nextChild;
        }		


		/*
		 * the default logic that traverses over the document tree doesn't
		 * handle classes that are in an @class attribute, so we deal with those
		 * individually.
		 */
		migratePersonObjects(document);
		migrateKualiCodeBaseObjects(document);
		migrateAccountExtensionObjects(document);
		migrateClassAsAttribute(document);
		removeAutoIncrementSetElements(document);
		removeReconcilerGroup(document);
		catchMissedTypedArrayListElements(document);

        TransformerFactory transFactory = TransformerFactory.newInstance();
        Transformer trans = transFactory.newTransformer();
        trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        trans.setOutputProperty(OutputKeys.INDENT, "yes");

        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        DOMSource source = new DOMSource(document);
        trans.transform(source, result);
		/*
		 * (?m) puts the regex into multiline mode:
		 * https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.
		 * html#MULTILINE So the effect of this statement is
		 * "remove any empty lines"
		 */
        xml = writer.toString().replaceAll("(?m)^\\s+\\n", "");
		xml = xml + "<" + MAINTENANCE_ACTION_ELEMENT_NAME + ">" + maintenanceAction + "</"
				+ MAINTENANCE_ACTION_ELEMENT_NAME + ">";

		// replace classnames not updated so far that were captured by smoke test below
		// Using context specific replacements in case match replacement pairs entered are too generic
		for (String className : classNameRuleMap.keySet()) {
			if (xml.contains("active defined-in=\"" + className + "\"")) {
				LOGGER.info("Replacing active defined-in= attribute: " + className + " with: "
						+ classNameRuleMap.get(className) + " at docid= " + docid);
				xml = xml.replace("active defined-in=\"" + className + "\"",
						"active defined-in=\"" + classNameRuleMap.get(className) + "\"");
			}
		}

		// Using context specific replacements in case match replacement pairs entered are too generic
		for (String className : classNameRuleMap.keySet()) {
			if (xml.contains("<" + className + ">")) {
				LOGGER.info("Replacing open tag: <" + className + "> with: <"
						+ classNameRuleMap.get(className) + ">" + " at docid= " + docid);
				xml = xml.replace("<" + className + ">",
						"<" + classNameRuleMap.get(className) + ">");
			}
		}

		// Using context specific replacements in case match replacement pairs entered are too generic
		for (String className : classNameRuleMap.keySet()) {
			if (xml.contains("</" + className + ">")) {
				LOGGER.info("Replacing close tag: </" + className + "> with: </"
						+ classNameRuleMap.get(className) + ">" + " at docid= " + docid);
				xml = xml.replace("</" + className + ">",
						"</" + classNameRuleMap.get(className) + ">");
			}
		}

		// replace classnames not updated so far that were captured by smoke test below
		// Using context specific replacements in case match replacement pairs entered are too generic
		for (String className : classNameRuleMap.keySet()) {
			if (xml.contains("maintainableImplClass=\"" + className + "\"")) {
				LOGGER.info("Replacing maintainableImplClass= attribute: " + className + " with: "
						+ classNameRuleMap.get(className) + " at docid= " + docid);
				xml = xml.replace("maintainableImplClass=\"" + className + "\"",
						"maintainableImplClass=\"" + classNameRuleMap.get(className) + "\"");
			}
		}

		// investigative logging, still useful as a smoke test
		for (String oldClassName : classNameRuleMap.keySet()) {
			if (xml.contains(oldClassName)) {
				LOGGER.warn("Document has classname in contents that should have been mapped: " + oldClassName);
				LOGGER.warn("at docid= " + docid + " for xml= " + xml);
			}
		}
		checkForElementsWithClassAttribute(document);
		return xml;
    }

	/*
	 * works more predictably and completely than existing traversal, however
	 * eats up much more time and resources (takes 6x longer to run, and pegs
	 * the toolsbox cpu the whole time)
	 */
	@SuppressWarnings("unused")
	private void doXpathTransformations(Document document) {
		/*
		 * note that order matters; if we map classnames first, class
		 * properties' XPaths will fail
		 */
		mapClassPropertiesWithXPath(document);
		mapClassNamesWithXPath(document);
		/*
		 * leaving date transformation in original traversal, and also leaving
		 * original as there is additional transformation business logic in that
		 * code. So some duplication, but much correctness
		 */
	}

	/**
	 * Map the classnames as described in {@link #classNameRuleMap}, finding the
	 * elements to transform via XPath against the top level {@link Document}.
	 * 
	 * @param document
	 */
	private void mapClassNamesWithXPath(Document document) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;
		LOGGER.trace("Doing XPath transformations for classnames.");
		for (String classname : classNameRuleMap.keySet()) {
			try {
				expr = xpath.compile("//" + classname);
				NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
				for (int i = 0; i < matchingNodes.getLength(); i++) {
					String mapTo = classNameRuleMap.get(classname);
					LOGGER.trace("Renaming class " + classname + " to " + mapTo);
					document.renameNode(matchingNodes.item(i), null, mapTo);
				}
			} catch (XPathExpressionException e) {
				LOGGER.error("XPathException encountered: ", e);
			}
		}

	}

	/**
	 * Map the class properties as described in {@link #classPropertyRuleMap},
	 * finding the elements to transform via XPath against the top level
	 * {@Link Document}.
	 * 
	 * @param document
	 */
	private void mapClassPropertiesWithXPath(Document document) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;
		LOGGER.trace("Doing XPath transformations for class properties.");
		for (String classname : classPropertyRuleMap.keySet()) {
			try {
				expr = xpath.compile("//" + classname);
				NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
				for (int i = 0; i < matchingNodes.getLength(); i++) {
					Map<String, String> propertyMappings = classPropertyRuleMap.get(classname);
					for (String propertyName : propertyMappings.keySet()) {
						expr = xpath.compile("//" + classname + "/" + propertyName);
						NodeList propertyNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
						for (int j = 0; j < propertyNodes.getLength(); j++) {
							String mapTo = propertyMappings.get(propertyName);
							/*
							 * if map target is empty, remove the node.
							 * Otherwise, rename to value.
							 */
							if (mapTo == null || mapTo.isEmpty()) {
								LOGGER.trace("Removing property " + propertyName);
								propertyNodes.item(j).getParentNode().removeChild(propertyNodes.item(j));
							} else {
								LOGGER.trace("Renaming property " + propertyName + " to " + mapTo);
								document.renameNode(propertyNodes.item(j), null, mapTo);
							}
						}
					}
				}
			} catch (XPathExpressionException e) {
				LOGGER.error("XPathException encountered: ", e);
			}
		}
	}

	/**
	 * There is an edge case in the main traversal such that a TypedArrayList
	 * element that is a child of a non-TypedArrayList element will be missed by
	 * processing. The traversal logic is tangled to the point that (correctly)
	 * fixing at that level will likely introduce more bugs, so doing cleanup
	 * after the fact instead.
	 * 
	 * @param document
	 * @throws InstantiationException
	 * @throws NoSuchMethodException
	 * @throws InvocationTargetException
	 * @throws IllegalAccessException
	 * @throws ClassNotFoundException
	 */
	private void catchMissedTypedArrayListElements(Document document) throws ClassNotFoundException,
			IllegalAccessException, InvocationTargetException, NoSuchMethodException, InstantiationException {
		LOGGER.trace("Cleaning up missed org.kuali.rice.kns.util.TypedArrayList elements.");
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;

		try {
			expr = xpath.compile("//*[@class='org.kuali.rice.kns.util.TypedArrayList']");
			NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
			for (int i = 0; i < matchingNodes.getLength(); i++) {
				handleTypedArrayList(document, xpath, (Element) matchingNodes.item(i));
			}
		} catch (XPathExpressionException e) {
			LOGGER.error("XPathException encountered: ", e);
		}
	}

	/**
	 * Migrate @class attributes which are missed by the main iteration
	 * 
	 * @param document
	 */
	private void migrateClassAsAttribute(Document document) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;
		for (String className : classNameRuleMap.keySet()) {
			try {
				String targetClassName = classNameRuleMap.get(className);
				expr = xpath.compile("//*[@class='" + className + "']");
				NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
				for (int i = 0; i < matchingNodes.getLength(); i++) {
					Node classAttr = matchingNodes.item(i).getAttributes().getNamedItem("class");
					classAttr.setNodeValue(targetClassName);
					LOGGER.trace("In element " + matchingNodes.item(i).getNodeName() + " migrating @class attribute of "
							+ className + " to " + targetClassName);
				}
			} catch (XPathExpressionException e) {
				LOGGER.error("XPathException encountered: ", e);
			}
		}
	}

	/**
	 * Migrate @maintainableImplClass attributes which are missed by the main
	 * iteration
	 * 
	 * @param document
	 */
	/*
	 * this has to be hacked around at the top-level before the DOM is even
	 * created; leaving code in place to hopefully someday do this at the DOM
	 * level
	 */
	@SuppressWarnings("unused")
	private void migrateMaintainableImplClassAsAttribute(Document document) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;
		for (String className : classNameRuleMap.keySet()) {
			try {
				String targetClassName = classNameRuleMap.get(className);
				expr = xpath.compile("//*[@maintainableImplClass='" + className + "']");
				NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
				for (int i = 0; i < matchingNodes.getLength(); i++) {
					Node classAttr = matchingNodes.item(i).getAttributes().getNamedItem("class");
					classAttr.setNodeValue(targetClassName);
					LOGGER.info("In element " + matchingNodes.item(i).getNodeName()
							+ " migrating @maintainableImplClass attribute of "
							+ className + " to " + targetClassName);
				}
			} catch (XPathExpressionException e) {
				LOGGER.error("XPathException encountered: ", e);
			}
		}
	}

	/*
	 * main traversal not finding and removing all autoIncrementSet elements, so
	 * cleaning up after it. TODO remove this method once main traversal is
	 * fixed
	 */
	private void removeAutoIncrementSetElements(Document document) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;
		try {
			expr = xpath.compile("//autoIncrementSet");
			NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
			for (int i = 0; i < matchingNodes.getLength(); i++) {
				Node match = matchingNodes.item(i);
				Node parent = match.getParentNode();
				LOGGER.trace("Removing element 'autoIncrementSet' in " + parent.getNodeName());
				parent.removeChild(match);
			}
		} catch (XPathExpressionException e) {
			LOGGER.error("XPathException encountered: ", e);
		}
	}

	@SuppressWarnings("unused")
	/*
	 * used when debugging, might need to re-enable, not throwing away code just
	 * yet
	 */
	private void removeAssetExtensionElements(Document document) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;
		try {
			expr = xpath.compile("//*[@class='edu.arizona.kfs.module.cam.businessobject.AssetExtension']");
			NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
			for (int i = 0; i < matchingNodes.getLength(); i++) {
				Node match = matchingNodes.item(i);
				Node parent = match.getParentNode();
				LOGGER.info("Removing element 'edu.arizona.kfs.module.cam.businessobject.AssetExtension' in "
						+ parent.getNodeName());
				parent.removeChild(match);
			}
		} catch (XPathExpressionException e) {
			LOGGER.error("XPathException encountered: ", e);
		}
	}
	/*
	 * UAF-5995
	 * Used to remove the reconcilerGroup and its child nodes for the ProcurementCardDefault doc
	 */
	private void removeReconcilerGroup(Document document) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;
		try {
			expr = xpath.compile("//reconcilerGroup");
			NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
			for (int i = 0; i < matchingNodes.getLength(); i++) {
				Node match = matchingNodes.item(i);
				Node parent = match.getParentNode();
				LOGGER.trace("Removing element 'reconcilerGroup' in " + parent.getNodeName());
				parent.removeChild(match);
			}
		} catch (XPathExpressionException e) {
			LOGGER.error("XPathException encountered: ", e);
		}
	}
	/**
	 * Investigative logging. Log if there are any elements with an \@class
	 * attribute
	 * 
	 * @param document
	 */
	private void checkForElementsWithClassAttribute(Document document) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression expr = null;
		try {
			expr = xpath.compile("//*[@class]");
			NodeList matchingNodes = (NodeList) expr.evaluate(document, XPathConstants.NODESET);
			for (int i = 0; i < matchingNodes.getLength(); i++) {
				String className = matchingNodes.item(i).getAttributes().getNamedItem("class").getTextContent();
				LOGGER.trace("In element " + matchingNodes.item(i).getNodeName() + " @class attribute of " + className);
			}
		} catch (XPathExpressionException e) {
			LOGGER.error("XPathException encountered: ", e);
		}

	}

	/**
	 * Upgrades the old Bo notes tag that was part of the maintainable to the
	 * new notes tag.
	 *
	 * @param oldXML
	 *            - the xml to upgrade
	 * @throws Exception
	 */
    private String upgradeBONotes(String oldXML, String docId) throws Exception {
        // Get the old bo note xml
        String notesXml = StringUtils.substringBetween(oldXML, "<boNotes>", "</boNotes>");
        if (notesXml != null) {
			LOGGER.trace("BO Notes present, upgrading -> " + docId);
            notesXml = notesXml.replace("org.kuali.rice.kns.bo.Note", "org.kuali.rice.krad.bo.Note");
            notesXml = "<org.apache.ojb.broker.core.proxy.ListProxyDefaultImpl>\n"
                    + notesXml
                    + "\n</org.apache.ojb.broker.core.proxy.ListProxyDefaultImpl>";
            
			int pos1 = oldXML.indexOf("<boNotes>");
			int pos2 = oldXML.indexOf("</boNotes>", pos1);

			if ((pos1 > -1) && (pos2 > pos1)) {
				oldXML = (oldXML.substring(0, pos1) + "\n<boNotes>\n" + notesXml + "\n</boNotes>"
						+ oldXML.substring(pos2 + "</boNotes>".length()));
			}
        }
        //replace empty boNotes if present
		oldXML = oldXML.replace("<boNotes/>", "");
        
        return oldXML;
    }
    
	/**
	 * Migrate any elements with the <code>class</code> containing
	 * <code>PersonImpl</code> from the provided {@link Document} if there is a
	 * mapping in {@link #classNameRuleMap}.
	 * 
	 * @param doc
	 */
    public void migratePersonObjects( Document doc ) {
        XPath xpath = XPathFactory.newInstance().newXPath();
        XPathExpression personProperties = null;
        try {
			String personImplClassName = null;
			for (String key : classNameRuleMap.keySet()) {
				if (key.endsWith("PersonImpl")) {
					personImplClassName = key;
				}
			}
			// if no mapping, nothing to do here
			if (personImplClassName == null) {
				return;
			}
			personProperties = xpath.compile("//*[@class='" + personImplClassName + "']");
            NodeList matchingNodes = (NodeList)personProperties.evaluate( doc, XPathConstants.NODESET );
            for(int i = 0; i < matchingNodes.getLength(); i++) {
                Node tempNode = matchingNodes.item(i);
				LOGGER.trace("Migrating PersonImpl node: " + tempNode.getNodeName() + "/" + tempNode.getNodeValue());
				// first, migrate address pieces to an EntityAddress node
				NodeList childNodes = tempNode.getChildNodes();
				String line1 = null, line2 = null, line3 = null, city = null, stateProvinceCode = null,
						postalCode = null, countryCode = null;
				for (int j = 0; j < childNodes.getLength(); j++) {
					Node child = childNodes.item(j);
					// FIXME magic strings
					if (child.getNodeName().equals("addressLine1")) {
						line1 = child.getTextContent();
						tempNode.removeChild(child);
						continue;
					} else if (child.getNodeName().equals("addressLine2")) {
						line2 = child.getTextContent();
						tempNode.removeChild(child);
						continue;
					} else if (child.getNodeName().equals("addressLine3")) {
						line3 = child.getTextContent();
						tempNode.removeChild(child);
						continue;
					} else if (child.getNodeName().equals("addressCityName")) {
						city = child.getTextContent();
						tempNode.removeChild(child);
						continue;
					} else if (child.getNodeName().equals("addressStateCode")) {
						stateProvinceCode = child.getTextContent();
						tempNode.removeChild(child);
						continue;
					} else if (child.getNodeName().equals("addressPostalCode")) {
						postalCode = child.getTextContent();
						tempNode.removeChild(child);
						continue;
					} else if (child.getNodeName().equals("addressCountryCode")) {
						countryCode = child.getTextContent();
						tempNode.removeChild(child);
					}
				}
            }
        } catch (XPathExpressionException e) {
			LOGGER.error("XPathException encountered: ", e);
        }
    }

	/**
	 * Migrate any elements with the <code>class</code> containing
	 * <code>KualiCodeBase</code> from the provided {@link Document} if there is
	 * a mapping in {@link #classNameRuleMap}.
	 * 
	 * @param doc
	 */
	public void migrateKualiCodeBaseObjects(Document doc) {
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression personProperties = null;
		try {
			String kualiCodeBaseClassName = null;
			for (String key : classNameRuleMap.keySet()) {
				if (key.endsWith("KualiCodeBase")) {
					kualiCodeBaseClassName = key;
				}
			}
			// if no mapping, nothing to do here
			if (kualiCodeBaseClassName == null) {
				return;
			}
			personProperties = xpath.compile("//*[@class='" + kualiCodeBaseClassName + "']");
			NodeList matchingNodes = (NodeList) personProperties.evaluate(doc, XPathConstants.NODESET);
			for (int i = 0; i < matchingNodes.getLength(); i++) {
				Node tempNode = matchingNodes.item(i);
				LOGGER.trace("Migrating KualiCodeBase node: " + tempNode.getNodeName() + "/" + tempNode.getNodeValue());
				String newClassName = this.classNameRuleMap.get(kualiCodeBaseClassName);
				doc.renameNode(tempNode, null, newClassName);
			}
		} catch (XPathExpressionException e) {
			LOGGER.error("XPathException encountered: ", e);
		}
	}

	/**
	 * TODO comment
	 * 
	 * @param doc
	 */
	public void migrateAccountExtensionObjects(Document doc) {
		// FIXME all sorts of magic strings
		XPath xpath = XPathFactory.newInstance().newXPath();
		XPathExpression accountExtensionProperties = null;
		try {
			accountExtensionProperties = xpath
					.compile("//*[@class='edu.arizona.kfs.coa.businessobject.AccountExtension']");
			NodeList matchingNodes = (NodeList) accountExtensionProperties.evaluate(doc, XPathConstants.NODESET);
			for (int i = 0; i < matchingNodes.getLength(); i++) {
				Node tempNode = matchingNodes.item(i);
				LOGGER.trace(
						"Migrating AccountExtension node: " + tempNode.getNodeName() + "/" + tempNode.getNodeValue());
				// migrate taxRegionCodeExt -> taxRegionCode
				NodeList children = tempNode.getChildNodes();
				for (int j = 0; j < children.getLength(); j++) {
					Node child = children.item(j);
					if (child.getNodeName().equals("taxRegionCodeExt")) {
						doc.renameNode(child, null, "taxRegionCode");
					}
				}
			}
		} catch (XPathExpressionException e) {
			LOGGER.error("XPathException encountered: ", e);
		}
	}

	/**
	 * If a mapping for the <code>node</code> class exists in
	 * {@link #classNameRuleMap}, the given <code>node</code> is renamed. Then,
	 * if the <code>node</code> is a valid class, it is passed to the
	 * {@link #transformNode(Document, Node, Class, Map)} method first with the
	 * {@link #classPropertyRuleMap} value for the classname, then with the
	 * {@link #classPropertyRuleMap} value for "<code>*</code>".
	 * 
	 * @param document
	 *            Root level {@link Document}
	 * @param node
	 *            {@link Node} to transform
	 * @throws ClassNotFoundException
	 * @throws XPathExpressionException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 * @throws NoSuchMethodException
	 * @throws InstantiationException
	 */
    private void transformClassNode(Document document, Node node) throws ClassNotFoundException, XPathExpressionException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, InstantiationException {

		String className = node.getNodeName();
		LOGGER.trace("Transforming class node for : " + node.getBaseURI() + "/" + className);
		if(this.classNameRuleMap.containsKey(className)) {
			String newClassName = this.classNameRuleMap.get(className);
			document.renameNode(node, null, newClassName);
			className = newClassName;
		}

        if (isValidClass(className)) {
            Class<?> dataObjectClass = Class.forName(className);

            if(classPropertyRuleMap.containsKey(className)) {
                transformNode(document, node, dataObjectClass, classPropertyRuleMap.get(className));
            }

            transformNode(document, node, dataObjectClass, classPropertyRuleMap.get("*"));
        }  else if ( xPathTransformationRulesMap.containsKey(className) ){
            //specific transformations for docs whose classes cannot be instantiated like in the above code.
            xPathApplyTransformations(document, className, xPathTransformationRulesMap.get(className) );
        }
	}

	/**
	 * Does the following:
	 * <ol>
	 * <li>Recursively calls this method on all child elements of
	 * <code>talist</code> to handle any child lists first
	 * <li>Remove the attributes {@link #SERIALIZATION_ATTRIBUTE} and
	 * {@link #CLASS_ATTRIBUTE} of <code>talist</code></li>
	 * <li>If
	 * <code>//[talist.getNodeName()]/org.apache.ojb.broker.core.proxy.ListProxyDefaultImpl/default/size/</code>
	 * evaluates to a value >1, indicating elements in this list, call
	 * {@link #transformClassNode(Document, Node)} on that element and store to
	 * readd</li>
	 * <li>Remove all child elements of <code>talist</code></li>
	 * <li>Readd list elements calculated and transformed above</li>
	 * </ol>
	 * 
	 * @param document
	 *            Root {@link Document}
	 * @param xpath
	 *            {@link XPath} to use during evaluation
	 * @param talist
	 *            {@link Element} to process typed array lists on
	 * @throws XPathExpressionException
	 * @throws ClassNotFoundException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 * @throws InvocationTargetException
	 * @throws NoSuchMethodException
	 * @throws InstantiationException
	 */
    private void handleTypedArrayList(Document document, XPath xpath , Element talist) throws XPathExpressionException, ClassNotFoundException, IllegalAccessException, InvocationTargetException, InvocationTargetException, NoSuchMethodException, InstantiationException {
		LOGGER.trace("Handling typed array list: " + talist.getNodeName());
		XPathExpression getChildTypedArrayLists = xpath
				.compile(".//*[@class='org.kuali.rice.kns.util.TypedArrayList']");
		NodeList nodeList = (NodeList) getChildTypedArrayLists.evaluate(talist, XPathConstants.NODESET);
        // handle any child lists first
        for (int i = 0; i < nodeList.getLength(); ++i) {
			Node item = nodeList.item(i);
			handleTypedArrayList(document, xpath, (Element) item);
        }
        
        talist.removeAttribute(SERIALIZATION_ATTRIBUTE);
        talist.removeAttribute(CLASS_ATTRIBUTE);
        XPathExpression listSizeExpression = xpath.compile("//" + talist.getNodeName() + "/org.apache.ojb.broker.core.proxy.ListProxyDefaultImpl/default/size/text()");
        String size = (String)listSizeExpression.evaluate(talist, XPathConstants.STRING);
        List<Node> nodesToAdd = new ArrayList<Node>();
        if(StringUtils.isNotBlank(size) && Integer.valueOf(size) > 0) {
            XPathExpression listTypeExpression = xpath.compile("//" + talist.getNodeName() + "/org.kuali.rice.kns.util.TypedArrayList/default/listObjectType/text()");
            String listType = (String)listTypeExpression.evaluate(talist, XPathConstants.STRING);
            XPathExpression listContentsExpression = xpath.compile("//" + talist.getNodeName() + "/org.apache.ojb.broker.core.proxy.ListProxyDefaultImpl/" + listType);
            NodeList listContents = (NodeList)listContentsExpression.evaluate(talist, XPathConstants.NODESET);
            for(int i = 0; i < listContents.getLength(); i++) {
                Node tempNode = listContents.item(i);
                transformClassNode(document, tempNode);
                nodesToAdd.add(tempNode);
            }
        }
        for(Node removeNode = talist.getFirstChild(); removeNode != null;) {
            Node nextRemoveNode = removeNode.getNextSibling();
            talist.removeChild(removeNode);
            removeNode = nextRemoveNode;
        }
        for(Node nodeToAdd : nodesToAdd) {
            talist.appendChild(nodeToAdd);
        }
    }
    
	/**
	 * For each child of <code>node</code>
	 * 
	 * @param document
	 * @param node
	 * @param currentClass
	 * @param propertyMappings
	 * @throws ClassNotFoundException
	 * @throws XPathExpressionException
	 * @throws IllegalAccessException
	 * @throws InvocationTargetException
	 * @throws NoSuchMethodException
	 * @throws InstantiationException
	 */
	private void transformNode(Document document, Node node, Class<?> currentClass, Map<String, String> propertyMappings) throws ClassNotFoundException, XPathExpressionException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, InstantiationException {
		LOGGER.trace("Transforming node: " + node.getBaseURI() + "/" + node.getNodeName());
		for(Node childNode = node.getFirstChild(); childNode != null;) {
			Node nextChild = childNode.getNextSibling();
			String propertyName = childNode.getNodeName();
			if(childNode.hasAttributes()) {
				XPath xpath = XPathFactory.newInstance().newXPath();
				Node serializationAttribute = childNode.getAttributes().getNamedItem(SERIALIZATION_ATTRIBUTE);
				if(serializationAttribute != null && StringUtils.equals(serializationAttribute.getNodeValue(), "custom")) {
					Node classAttribute = childNode.getAttributes().getNamedItem(CLASS_ATTRIBUTE);
					if(classAttribute != null && StringUtils.equals(classAttribute.getNodeValue(), "org.kuali.rice.kns.util.TypedArrayList")) {
                        handleTypedArrayList(document, xpath, (Element)childNode);
					} else if (isTargetEffortCertificationReportPositionsNode(childNode)) {
						// Need to skip over ECRD positions list due to needing serialization attr
						// that otherwise was getting stripped on line 924. This also avoids a child
						// list node from getting errantly pruned off ECRD doc types
						deleteAllNoneListProxyChildren(childNode);
					} else {
						((Element)childNode).removeAttribute(SERIALIZATION_ATTRIBUTE);
						
						XPathExpression mapContentsExpression = xpath.compile("//" + propertyName + "/map/string");
						NodeList mapContents = (NodeList)mapContentsExpression.evaluate(childNode, XPathConstants.NODESET);
						List<Node> nodesToAdd = new ArrayList<Node>();
						if(mapContents.getLength() > 0 && mapContents.getLength() % 2 == 0) {
							for(int i = 0; i < mapContents.getLength(); i++) {
								Node keyNode = mapContents.item(i);
								Node valueNode = mapContents.item(++i);
								Node entryNode = document.createElement("entry");
								entryNode.appendChild(keyNode);
								entryNode.appendChild(valueNode);
								nodesToAdd.add(entryNode);
							}
						}
						for(Node removeNode = childNode.getFirstChild(); removeNode != null;) {
							Node nextRemoveNode = removeNode.getNextSibling();
							childNode.removeChild(removeNode);
							removeNode = nextRemoveNode;
						}
						for(Node nodeToAdd : nodesToAdd) {
							childNode.appendChild(nodeToAdd);
						}
					}
				}
			}
			if(propertyMappings != null && propertyMappings.containsKey(propertyName)) {
				String newPropertyName = propertyMappings.get(propertyName);
				if(StringUtils.isNotBlank(newPropertyName)) {
					document.renameNode(childNode, null, newPropertyName);
					propertyName = newPropertyName;
				} else {
					// If there is no replacement name then the element needs
					// to be removed and skip all other processing
					node.removeChild(childNode);
					childNode = nextChild;
					continue;
				}
			}

            if(dateRuleMap != null && dateRuleMap.containsKey(propertyName)) {
                String newDateValue = dateRuleMap.get(propertyName);
                if(StringUtils.isNotBlank(newDateValue)) {
                    if ( childNode.getTextContent().length() == 10 ) {
                        childNode.setTextContent( childNode.getTextContent() + " " + newDateValue );

                    }
                }
            }

            if ((currentClass != null) && isValidClass(currentClass)) {
                if (childNode.hasChildNodes() && !(Collection.class.isAssignableFrom(currentClass) || Map.class
                        .isAssignableFrom(currentClass))) {
                  PropertyClassKey key = new PropertyClassKey(currentClass, propertyName);
                  Optional<Class<?>> propertyClass = propertyClassCache.getUnchecked(key);
                  if (propertyClass.isPresent() && classPropertyRuleMap.containsKey(propertyClass.get().getName())) {
                    transformNode(document, childNode, propertyClass.get(), this.classPropertyRuleMap.get(propertyClass.get().getName()));
                  }
                  transformNode(document, childNode, propertyClass.orNull(), classPropertyRuleMap.get("*"));
                }
            }
			childNode = nextChild;
		}
	}
    
	/**
	 * Storefront to call {@link #isValidClass(String)} with
	 * {@link Class#getName()}
	 * 
	 * @param c
	 * @return value of {@link #isValidClass(String)} with
	 *         {@link Class#getName()}
	 */
	private boolean isValidClass(Class<?> c) {
        return isValidClass(c.getName());
    }

	/**
	 * @param className
	 *            {@link String} of a classname to check the validity of
	 * @return <code>true</code> if <code>className</code> does NOT start with
	 *         <code>edu.arizona</code> or <code>com.rsmart.</code>, and
	 *         <code>className</code> is not in the {@link #ignoreClassSet}.
	 */
    private boolean isValidClass(String className) {
        if (className.startsWith("edu.arizona") || className.startsWith("com.rsmart.")) {
            if (!uaMaintenanceDocClasses.contains(className)) {
                uaMaintenanceDocClasses.add(className);
				LOGGER.info("non-kuali maintenance document class ignored - " + className);
            }
            return false;
        } else {
            return !ignoreClassSet.contains(className);
        }
    }
    /**
     * Reads the rule xml and sets up the rule maps that will be used to transform the xml
     */
	private void setRuleMaps() throws Exception {
		setupConfigurationMaps();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();

        Document doc = db.parse(rulesXmlFile.openBufferedStream());

        doc.getDocumentElement().normalize();
        XPath xpath = XPathFactory.newInstance().newXPath();

        // Get the moved classes rules
        XPathExpression exprClassNames = xpath.compile("//*[@name='maint_doc_classname_changes']/pattern");
        NodeList classNamesList = (NodeList) exprClassNames.evaluate(doc, XPathConstants.NODESET);
        for (int s = 0; s < classNamesList.getLength(); s++) {
            String matchText = xpath.evaluate("match/text()", classNamesList.item(s));
            String replaceText = xpath.evaluate("replacement/text()", classNamesList.item(s));
            classNameRuleMap.put(matchText, replaceText);
        }

        // Get the property changed rules
        XPathExpression exprClassProperties = xpath.compile(
                "//*[@name='maint_doc_changed_class_properties']/pattern");
        XPathExpression exprClassPropertiesPatterns = xpath.compile("pattern");
        NodeList propertyClassList = (NodeList) exprClassProperties.evaluate(doc, XPathConstants.NODESET);
        for (int s = 0; s < propertyClassList.getLength(); s++) {
            String classText = xpath.evaluate("class/text()", propertyClassList.item(s));
            Map<String, String> propertyRuleMap = new HashMap<String, String>();
            NodeList classPropertiesPatterns = (NodeList) exprClassPropertiesPatterns.evaluate(
                    propertyClassList.item(s), XPathConstants.NODESET);
            for (int c = 0; c < classPropertiesPatterns.getLength(); c++) {
                String matchText = xpath.evaluate("match/text()", classPropertiesPatterns.item(c));
                String replaceText = xpath.evaluate("replacement/text()", classPropertiesPatterns.item(c));
                propertyRuleMap.put(matchText, replaceText);
            }
            classPropertyRuleMap.put(classText, propertyRuleMap);
        }

        // Get the Date rules
        XPathExpression dateFieldNames = xpath.compile("//*[@name='maint_doc_date_changes']/pattern");
        NodeList DateNamesList = (NodeList) dateFieldNames.evaluate(doc, XPathConstants.NODESET);
        for (int s = 0; s < DateNamesList.getLength(); s++) {
            String matchText = xpath.evaluate("match/text()", DateNamesList.item(s));
            String replaceText = xpath.evaluate("replacement/text()", DateNamesList.item(s));
            dateRuleMap.put(matchText, replaceText);
        }

        // Get the specific XPath rules
        XPathExpression xpathClassProperties = xpath.compile("//*[@name='xpath_specific_changes']/pattern");
        XPathExpression xPathClassPatterns = xpath.compile("pattern");
        NodeList xpathClassList = (NodeList) xpathClassProperties.evaluate(doc, XPathConstants.NODESET);
        for (int s = 0; s < xpathClassList.getLength(); s++) {
            String classText = xpath.evaluate("class/text()", xpathClassList.item(s));
            Map<String, String> xpathRuleMap = new HashMap<String, String>();
            NodeList patterns = (NodeList) xPathClassPatterns.evaluate(xpathClassList.item(s), XPathConstants.NODESET);
            for (int c = 0; c < patterns.getLength(); c++) {
                String matchPattern = xpath.evaluate("match/text()", patterns.item(c));
                String replace = xpath.evaluate("replacement/text()", patterns.item(c));
                xpathRuleMap.put(matchPattern, replace);
            }
            xPathTransformationRulesMap.put(classText, xpathRuleMap);
        }
	}

	/**
	 * Constructs the various instance variable maps and pre-populates
	 * {@link #classPropertyRuleMap} with some values which apply to all BOs.
	 */
	private void setupConfigurationMaps() {
		classNameRuleMap = new HashMap<String, String>();
		classPropertyRuleMap = new HashMap<String, Map<String,String>>();
        dateRuleMap = new HashMap<String, String>();
        xPathTransformationRulesMap = new HashMap<String, Map<String,String>>();

        // Pre-populate the class property rules with some defaults which apply to every BO
		Map<String, String> defaultPropertyRules = new HashMap<String, String>();
		defaultPropertyRules.put("boNotes", null);
		defaultPropertyRules.put("autoIncrementSet", null);
        classPropertyRuleMap.put("*", defaultPropertyRules);
	}


    private void xPathApplyTransformations(Document document, String className, Map<String, String> replacementRules ) {
        for ( String matchPattern: replacementRules.keySet() ){
            replaceNode(document, "//"+className+"/"+matchPattern, replacementRules.get( matchPattern) );
        }
    }

    private void replaceNode(Document document, String pathToNode, String newNodeName) {
        LOGGER.trace("Replacing path " + pathToNode + " : with " + newNodeName);
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            XPathExpression nodesFilter = xpath.compile(pathToNode);
            NodeList matchingNodes = (NodeList) nodesFilter.evaluate(document, XPathConstants.NODESET);
            for (int i = 0; i < matchingNodes.getLength(); i++) {
                Node tempNode = matchingNodes.item(i);
                LOGGER.trace("Migrating node: " + tempNode.getNodeName() + "/" + tempNode.getTextContent());
                document.renameNode(tempNode, null, newNodeName);
            }
        } catch (XPathExpressionException e) {
            LOGGER.error("XPathException encountered: ", e);
        }
    }

    /*
     * True only when the node passed has the target ECRD's child node name,
     * AND the parent is named the ECRD root node name. This should help to ensure
     * we only test positive for the ECRD target as intended. A true effectively
     * means a needed serialization attribute is not deleted, and that a child
     * list node does not get pruned from the ECRD maintenance XML (happens in
     * the calling method in the next 'else' case after the call here).
     */
	private boolean isTargetEffortCertificationReportPositionsNode(Node node) {
		if (node == null
				|| StringUtils.isBlank(node.getNodeName())
				|| node.getParentNode() == null
				|| StringUtils.isBlank(node.getParentNode().getNodeName())) {
			return false;
		}

		String nodeName = node.getNodeName();
		String parentNodeName = node.getParentNode().getNodeName();

		return nodeName.equals(ECRD_CHILD_NODE_NAME)
				&& parentNodeName.equals(ECRD_ROOT_NODE_NAME);
	}

	/*
	 * Everything but ListProxyDefaultImpl node should be removed from input. A call to this
	 * should always be preceded by a call to isTargetEffortCertificationReportPositionsNode(),
	 * or similar guard.
	 */
	private void deleteAllNoneListProxyChildren(Node ecrdPositionsNode) {
		Node childNode = ecrdPositionsNode.getFirstChild();
		while(childNode != null) {
			if (!childNode.getNodeName().equals(LIST_PROXY_NAME)) {
				// Not a list proxy node, so remove it
				ecrdPositionsNode.removeChild(childNode);
			}
			childNode = childNode.getNextSibling();
		}
	}

}
