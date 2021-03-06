/*-****************************************************************************** 
 * Copyright (c) 2014 Iwao AVE!.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Iwao AVE! - initial API and implementation and/or initial documentation
 *******************************************************************************/

package net.harawata.mybatipse.mybatis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.xpath.XPathExpressionException;

import net.harawata.mybatipse.Activator;
import net.harawata.mybatipse.bean.BeanPropertyCache;
import net.harawata.mybatipse.mybatis.JavaMapperUtil.MapperMethodInfo;
import net.harawata.mybatipse.util.XpathUtil;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.wst.sse.core.StructuredModelManager;
import org.eclipse.wst.sse.core.internal.provisional.IStructuredModel;
import org.eclipse.wst.sse.core.internal.provisional.text.IStructuredDocument;
import org.eclipse.wst.validation.AbstractValidator;
import org.eclipse.wst.validation.ValidationResult;
import org.eclipse.wst.validation.ValidationState;
import org.eclipse.wst.validation.ValidatorMessage;
import org.eclipse.wst.validation.internal.provisional.core.IReporter;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMAttr;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMDocument;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMElement;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMModel;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMText;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Iwao AVE!
 */
@SuppressWarnings("restriction")
public class XmlValidator extends AbstractValidator
{
	public static final String MARKER_ID = "net.harawata.mybatipse.XmlProblem";

	public static final String MISSING_TYPE = "missingType";

	public static final String NO_WRITABLE_PROPERTY = "noWritableProperty";

	public static final String MISSING_TYPE_HANDLER = "missingTypeHandler";

	public static final String MISSING_STATEMENT_METHOD = "missingStatementMethod";

	public static final String MISSING_RESULT_MAP = "missingResultMap";

	public static final String MISSING_SQL = "missingSql";

	public static final String MISSING_NAMESPACE = "missingNamespace";

	public static final String NAMESPACE_MANDATORY = "namespaceMandatory";

	public static final String DEPRECATED = "deprecated";

	private static final List<String> validatableTags = Arrays.asList("id", "idArg", "result",
		"arg", "resultMap", "collection", "association", "select", "insert", "update", "delete",
		"include", "cache", "typeAlias", "typeHandler", "objectFactory", "objectWrapperFactory",
		"plugin", "transactionManager", "mapper", "package", "databaseIdProvider", "foreach");

	private static final Set<String> validatableTextTags = new HashSet<String>(Arrays.asList(
		"select", "insert", "update", "delete", "if", "foreach", "when", "otherwise"));

	private static Pattern statementTextPropertyRefPattern = Pattern.compile("[#$]\\{[\\s]*([^,}\\s]*)[\\s]*[,}]");

	public void cleanup(IReporter reporter)
	{
		// Nothing to do.
	}

	/**
	 * Determine if a given file should be validated: Should validate if not derived, not team
	 * private or not in such folder, is accessible and does not start with a dot. Code copied
	 * from AbstractNestedValidator
	 * 
	 * @param file The file that may be validated.
	 * @return True if the file should be validated, false otherwise.
	 */
	private static boolean shouldValidate(IResource file)
	{
		IResource resource = file;
		do
		{
			if (resource.isDerived() || resource.isTeamPrivateMember() || !resource.isAccessible()
				|| resource.getName().charAt(0) == '.')
			{
				return false;
			}
			resource = resource.getParent();
		}
		while ((resource.getType() & IResource.PROJECT) == 0);

		return true;
	}

	@Override
	public ValidationResult validate(final IResource resource, int kind, ValidationState state,
		IProgressMonitor monitor)
	{
		if (resource.getType() != IResource.FILE)
			return null;
		if (!shouldValidate(resource))
			return null;
		ValidationResult result = new ValidationResult();
		final IReporter reporter = result.getReporter(monitor);
		validateFile((IFile)resource, reporter, result);
		return result;
	}

	private void validateFile(IFile file, IReporter reporter, ValidationResult result)
	{
		if ((reporter != null) && (reporter.isCancelled() == true))
		{
			throw new OperationCanceledException();
		}
		IStructuredModel model = null;
		try
		{
			file.deleteMarkers(MARKER_ID, false, IResource.DEPTH_ZERO);
			model = StructuredModelManager.getModelManager().getModelForRead(file);
			IDOMModel domModel = (IDOMModel)model;
			IDOMDocument domDoc = domModel.getDocument();
			NodeList nodes = domDoc.getChildNodes();

			IJavaProject project = JavaCore.create(file.getProject());

			for (int k = 0; k < nodes.getLength(); k++)
			{
				Node child = nodes.item(k);
				if (child instanceof IDOMElement)
				{
					validateElement(project, (IDOMElement)child, file, domDoc, reporter, result,
						MybatipseXmlUtil.getNamespace(domDoc), null, null, null);
				}
			}
		}
		catch (Exception e)
		{
			Activator.log(Status.WARNING, "Error occurred during validation.", e);
		}
		finally
		{
			if (model != null)
			{
				model.releaseFromRead();
			}
		}
	}

	private void validateElement(IJavaProject project, IDOMElement element, IFile file,
		IDOMDocument doc, IReporter reporter, ValidationResult result, String namespace,
		Node statementNode, String forEachItem, String forEachType) throws JavaModelException,
		XPathExpressionException
	{
		if ((reporter != null) && (reporter.isCancelled() == true))
		{
			throw new OperationCanceledException();
		}
		if (element == null)
			return;

		String tagName = element.getNodeName();

		if (validatableTags.contains(tagName))
		{
			NamedNodeMap attrs = element.getAttributes();
			for (int i = 0; i < attrs.getLength(); i++)
			{
				IDOMAttr attr = (IDOMAttr)attrs.item(i);
				String attrName = attr.getName();
				String attrValue = attr.getValue().trim();

				// TODO: proxyFactory, logImpl, package
				if (("type".equals(attrName) && !"dataSource".equals(tagName))
					|| "resultType".equals(attrName) || "parameterType".equals(attrName)
					|| "ofType".equals(attrName) || "typeHandler".equals(attrName)
					|| "handler".equals(attrName) || "interceptor".equals(attrName)
					|| "class".equals(attrName) || "javaType".equals(attrName))
				{
					String qualifiedName = MybatipseXmlUtil.normalizeTypeName(attrValue);
					validateJavaType(project, file, doc, attr, qualifiedName, result, reporter);
				}
				else if ("property".equals(attrName))
				{
					validateProperty(element, file, doc, result, project, attr, attrValue, reporter);
				}
				else if ("collection".equals(attrName) && "foreach".equals(tagName))
				{
					forEachType = validateForEachCollection(project, attr, file, doc, reporter, result,
						namespace, statementNode);
					if (forEachType != null)
					{
						Node namedItem = attrs.getNamedItem("item");
						if (namedItem != null)
						{
							forEachItem = namedItem.getNodeValue();
						}
					}
				}
				else if ("id".equals(attrName)
					&& ("select".equals(tagName) || "update".equals(tagName) || "insert".equals(tagName) || "delete".equals(tagName)))
				{
					statementNode = element;
					validateStatementId(element, file, doc, result, project, attr, attrValue, namespace);
				}
				else if ("resultMap".equals(attrName) || "resultMap".equals(attrName))
				{
					validateResultMapId(project, file, doc, result, attr, attrValue, namespace, reporter);
				}
				else if ("refid".equals(attrName))
				{
					validateSqlId(project, file, doc, result, attr, attrValue, namespace, reporter);
				}
				else if ("select".equals(attrName))
				{
					validateSelectId(project, file, doc, result, attr, attrValue, namespace, reporter);
				}
				else if ("namespace".equals(attrName))
				{
					validateNamespace(file, doc, result, attr, attrValue);
				}
				else if ("parameterMap".equals(tagName))
				{
					warnDeprecated(file, doc, result, tagName, attr);
				}
				else if ("parameter".equals(attrName) || "parameterMap".equals(attrName))
				{
					warnDeprecated(file, doc, result, attrName, attr);
				}
			}
		}

		NodeList nodes = element.getChildNodes();
		boolean hasText = validatableTextTags.contains(element.getNodeName());
		for (int j = 0; j < nodes.getLength(); j++)
		{
			Node child = nodes.item(j);
			if (child instanceof IDOMElement)
			{
				validateElement(project, (IDOMElement)child, file, doc, reporter, result, namespace,
					statementNode, forEachItem, forEachType);
			}
			else if (hasText && child instanceof IDOMText)
			{
				validateTextMayContainPropertyRefs(project, (IDOMText)child, file, doc, reporter,
					result, namespace, statementNode, forEachItem, forEachType);
			}
		}
	}

	private String validateForEachCollection(IJavaProject project, IDOMAttr collectionAttr,
		IFile file, IDOMDocument doc, IReporter reporter, ValidationResult result,
		String mapperFqn, Node statementNode)
	{
		if (statementNode != null)
		{
			// found enclosing statement node
			String statementId = null;
			NamedNodeMap statementAttrs = statementNode.getAttributes();
			for (int i = 0; i < statementAttrs.getLength(); i++)
			{
				Node attr = statementAttrs.item(i);
				String attrName = attr.getNodeName();
				if ("id".equals(attrName))
					statementId = attr.getNodeValue();
			}

			if (statementId != null && !statementId.isEmpty())
			{
				// found statmenet id, check method

				final List<MapperMethodInfo> methodInfos = new ArrayList<MapperMethodInfo>();
				JavaMapperUtil.findMapperMethod(methodInfos, project, mapperFqn, statementId, true,
					true);

				if (methodInfos.size() > 0)
				{
					// found method, can validate
					String forEachType = validatePropertyRef(project, methodInfos.get(0).getParams(),
						mapperFqn + "." + statementId, file, doc, result,
						collectionAttr.getValueRegionStartOffset(), collectionAttr.getNodeValue(), 0, null,
						null);

					if (forEachType.endsWith("[]"))
						forEachType = forEachType.substring(0, forEachType.length() - 2);
					else if (forEachType.startsWith("java.util.List<"))
						forEachType = forEachType.substring(15, forEachType.length() - 1);
					return forEachType;
				}
			}
		}
		return null;
	}

	private void validateTextMayContainPropertyRefs(IJavaProject project, IDOMText child,
		IFile file, IDOMDocument doc, IReporter reporter, ValidationResult result,
		String mapperFqn, Node statementNode, String forEachItem, String forEachType)
	{
		if (statementNode != null)
		{
			// found enclosing statement node
			String statementId = null;
			NamedNodeMap statementAttrs = statementNode.getAttributes();
			for (int i = 0; i < statementAttrs.getLength(); i++)
			{
				Node attr = statementAttrs.item(i);
				String attrName = attr.getNodeName();
				if ("id".equals(attrName))
					statementId = attr.getNodeValue();
			}

			if (statementId != null && !statementId.isEmpty())
			{
				// found statmenet id, check method
				final List<MapperMethodInfo> methodInfos = new ArrayList<MapperMethodInfo>();
				JavaMapperUtil.findMapperMethod(methodInfos, project, mapperFqn, statementId, true,
					true);

				if (methodInfos.size() > 0)
				{
					// found method, can validate
					String textContent = child.getTextContent();
					int startOffset = child.getStartStructuredDocumentRegion().getStartOffset();
					Matcher matcher = statementTextPropertyRefPattern.matcher(textContent);
					Map<String, String> paramMap = methodInfos.get(0).getParams();
					if (forEachItem != null && forEachType != null)
					{
						paramMap = new HashMap<String, String>(paramMap);
						paramMap.put(forEachItem, forEachType);
					}
					while (matcher.find())
					{
						String property = matcher.group(1);
						int propertyStartOffset = matcher.start(1);
						validatePropertyRef(project, paramMap, mapperFqn + "." + statementId, file, doc,
							result, startOffset, property, propertyStartOffset, forEachItem, forEachType);
					}
				}
			}
		}
	}

	private String validatePropertyRef(IJavaProject project, Map<String, String> paramMap,
		String mapperMethod, IFile file, IDOMDocument doc, ValidationResult result,
		int startOffset, String property, int propertyStartOffset, String forEachItem,
		String forEachType)
	{
		int propertyLength = property.length();

		String propertyType = null;

		if (paramMap.size() == 1)
		{
			// If there is only one parameter with no @Param,
			// properties should be directly referenced.
			String paramType = paramMap.values().iterator().next();
			String paramName = paramMap.keySet().iterator().next();
			Map<String, String> fields = BeanPropertyCache.searchFields(project, paramType, property,
				true, -1, true);
			int lastDot = property.lastIndexOf('.');
			String matchProperty = lastDot < 0 ? property : property.substring(lastDot + 1);
			if (matchProperty.endsWith("[0]"))
			{
				matchProperty = matchProperty.substring(0, matchProperty.length() - 3);
			}
			propertyType = fields.get(matchProperty);
			if (propertyType == null)
			{
				if (paramName.equals("param1") && lastDot < 0)
				{
					// allow anything: non-named parameter, any reference can be resolved
				}
				else
				{
					// not valid property
					addMarker(result, file, doc.getStructuredDocument(), startOffset
						+ propertyStartOffset, propertyLength, property, MISSING_TYPE,
						IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Property '" + property
							+ "' not found in class " + paramType);
				}
			}
		}
		else if (paramMap.size() > 1)
		{
			int dotPos = property.indexOf('.');
			if (dotPos == -1)
			{
				if (property.endsWith("[0]"))
				{
					property = property.substring(0, property.length() - 3);
				}
				propertyType = paramMap.get(property);
				if (propertyType == null)
				{
					// not valid parameter
					addMarker(result, file, doc.getStructuredDocument(), startOffset
						+ propertyStartOffset, propertyLength, property, MISSING_TYPE,
						IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Parameter '" + property
							+ "' not found as @Param in method " + mapperMethod);
				}
			}
			else
			{
				String paramName = property.substring(0, dotPos);
				propertyType = paramMap.get(paramName);
				if (propertyType == null)
				{
					// not valid parameter
					addMarker(result, file, doc.getStructuredDocument(), startOffset
						+ propertyStartOffset, propertyLength, property, MISSING_TYPE,
						IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Parameter '" + paramName
							+ "' not found as @Param in method " + mapperMethod);
				}
				else
				{
					// check type
					property = property.substring(dotPos + 1);
					Map<String, String> fields = BeanPropertyCache.searchFields(project, propertyType,
						property, true, -1, true);
					int lastDot = property.lastIndexOf('.');
					String matchProperty = lastDot < 0 ? property : property.substring(lastDot + 1);
					if (matchProperty.endsWith("[0]"))
					{
						matchProperty = matchProperty.substring(0, matchProperty.length() - 3);
					}
					propertyType = fields.get(matchProperty);
					if (propertyType == null)
					{
						// not valid property
						addMarker(result, file, doc.getStructuredDocument(), startOffset
							+ propertyStartOffset, propertyLength, property, MISSING_TYPE,
							IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Property '" + property
								+ "' not found in class " + propertyType);
					}
				}
			}
		}

		return propertyType;
	}

	private void validateNamespace(IFile file, IDOMDocument doc, ValidationResult result,
		IDOMAttr attr, String attrValue)
	{
		if (attrValue == null || attrValue.length() == 0)
		{
			addMarker(result, file, doc.getStructuredDocument(), attr, NAMESPACE_MANDATORY,
				IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Namespace must be specified.");
		}
	}

	private void validateResultMapId(IJavaProject project, IFile file, IDOMDocument doc,
		ValidationResult result, IDOMAttr attr, String attrValue, String namespace,
		IReporter reporter) throws JavaModelException
	{
		if (attrValue.indexOf(',') == -1)
		{
			validateReference(project, file, doc, result, attr, attrValue, namespace, "resultMap",
				reporter);
		}
		else
		{
			String[] resultMapArr = attrValue.split(",");
			for (String resultMapRef : resultMapArr)
			{
				String ref = resultMapRef.trim();
				if (ref.length() > 0)
				{
					validateReference(project, file, doc, result, attr, ref, namespace, "resultMap",
						reporter);
				}
			}
		}
	}

	private void validateSelectId(IJavaProject project, IFile file, IDOMDocument doc,
		ValidationResult result, IDOMAttr attr, String attrValue, String namespace,
		IReporter reporter) throws JavaModelException
	{
		validateReference(project, file, doc, result, attr, attrValue, namespace, "select",
			reporter);
	}

	private void validateSqlId(IJavaProject project, IFile file, IDOMDocument doc,
		ValidationResult result, IDOMAttr attr, String attrValue, String namespace,
		IReporter reporter) throws JavaModelException
	{
		validateReference(project, file, doc, result, attr, attrValue, namespace, "sql", reporter);
	}

	private void validateReference(IJavaProject project, IFile file, IDOMDocument doc,
		ValidationResult result, IDOMAttr attr, String attrValue, String qualifiedName,
		String targetElement, IReporter reporter) throws JavaModelException
	{
		try
		{
			if (attrValue.indexOf('$') > -1)
				return;

			if (attrValue.indexOf('.') == -1)
			{
				// Internal reference
				if ("select".equals(targetElement))
				{
					if (mapperMethodExists(project, qualifiedName, attrValue))
					{
						return;
					}
				}
				String xpath = "count(//" + targetElement + "[@id='" + attrValue + "']) > 0";
				if (!XpathUtil.xpathBool(doc, xpath))
				{
					addMarker(result, file, doc.getStructuredDocument(), attr, MISSING_SQL,
						IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, targetElement + " with id='"
							+ attrValue + "' not found.");
				}
			}
			else
			{
				// External reference
				int lastDot = attrValue.lastIndexOf('.');
				String namespace = attrValue.substring(0, lastDot);
				String statementId = attrValue.substring(lastDot + 1);
				if ("select".equals(targetElement)
					&& mapperMethodExists(project, namespace, statementId))
				{
					return;
				}
				IFile mapperFile = MapperNamespaceCache.getInstance().get(project, namespace, reporter);
				if (mapperFile == null)
				{
					addMarker(result, file, doc.getStructuredDocument(), attr, MISSING_NAMESPACE,
						IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Namespace='" + namespace
							+ "' not found.");
				}
				else
				{
					String xpath = "count(//" + targetElement + "[@id='" + statementId + "']) > 0";
					if (!isElementExists(mapperFile, xpath))
					{
						addMarker(result, file, doc.getStructuredDocument(), attr, MISSING_SQL,
							IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, targetElement + " with id='"
								+ attrValue + "' not found.");
					}
				}
			}
		}
		catch (XPathExpressionException e)
		{
			Activator.log(Status.ERROR, "Error while searching sql with id = " + attrValue, e);
		}
	}

	private void validateStatementId(IDOMElement element, IFile file, IDOMDocument doc,
		ValidationResult result, IJavaProject project, IDOMAttr attr, String attrValue,
		String qualifiedName) throws JavaModelException, XPathExpressionException
	{
		if (attrValue == null)
		{
			return;
		}

		IType mapperType = project.findType(qualifiedName);
		if (mapperType != null && !mapperMethodExists(project, qualifiedName, attrValue))
		{
			addMarker(result, file, doc.getStructuredDocument(), attr, MISSING_STATEMENT_METHOD,
				IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Method '" + attrValue
					+ "' not found or there is an overload method"
					+ " (same name with different parameters) in mapper interface " + qualifiedName);
		}
	}

	private boolean mapperMethodExists(IJavaProject project, String qualifiedName,
		String methodName) throws JavaModelException
	{
		List<MapperMethodInfo> methodInfos = new ArrayList<MapperMethodInfo>();
		JavaMapperUtil.findMapperMethod(methodInfos, project, qualifiedName, methodName, true, true);
		return methodInfos.size() == 1;
	}

	private void validateProperty(IDOMElement element, IFile file, IDOMDocument doc,
		ValidationResult result, IJavaProject project, IDOMAttr attr, String attrValue,
		IReporter reporter) throws JavaModelException
	{
		String qualifiedName = MybatipseXmlUtil.findEnclosingType(element);
		if (MybatipseXmlUtil.isDefaultTypeAlias(qualifiedName))
		{
			return;
		}
		IType type = project.findType(qualifiedName);
		if (type == null)
		{
			qualifiedName = TypeAliasCache.getInstance().resolveAlias(project, qualifiedName,
				reporter);
			if (qualifiedName != null)
				type = project.findType(qualifiedName);
		}
		if (type == null || !isValidatable(project, type))
		{
			// Skip validation when enclosing type is missing or it's a map.
			return;
		}
		Map<String, String> fields = BeanPropertyCache.searchFields(project, qualifiedName,
			attrValue, false, -1, true);
		if (fields.size() == 0)
		{
			addMarker(result, file, doc.getStructuredDocument(), attr, MISSING_TYPE,
				IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Property '" + attrValue
					+ "' not found in class " + qualifiedName);
		}
	}

	private boolean isValidatable(IJavaProject project, IType type) throws JavaModelException
	{
		// Subclass of Map is not validatable.
		IType map = project.findType("java.util.Map");
		return !isAssignable(type, map);
	}

	private boolean isAssignable(IType type, IType targetType) throws JavaModelException
	{
		final ITypeHierarchy supertypes = type.newSupertypeHierarchy(new NullProgressMonitor());
		return supertypes.contains(targetType);
	}

	private void validateJavaType(IJavaProject project, IFile file, IDOMDocument doc,
		IDOMAttr attr, String qualifiedName, ValidationResult result, IReporter reporter)
		throws JavaModelException
	{
		if (!MybatipseXmlUtil.isDefaultTypeAlias(qualifiedName)
			&& project.findType(qualifiedName) == null
			&& TypeAliasCache.getInstance().resolveAlias(project, qualifiedName, reporter) == null)
		{
			addMarker(result, file, doc.getStructuredDocument(), attr, MISSING_TYPE,
				IMarker.SEVERITY_ERROR, IMarker.PRIORITY_HIGH, "Class/TypeAlias '" + qualifiedName
					+ "' not found.");
		}
	}

	private void warnDeprecated(IFile file, IDOMDocument doc, ValidationResult result,
		String tagName, IDOMAttr attr)
	{
		addMarker(result, file, doc.getStructuredDocument(), attr, DEPRECATED,
			IMarker.SEVERITY_WARNING, IMarker.PRIORITY_HIGH, "'" + tagName
				+ "' is deprecated and should not be used.");
	}

	private void addMarker(ValidationResult result, IFile file, IStructuredDocument doc,
		IDOMAttr attr, String problemType, int severity, int priority, String message)
	{
		addMarker(result, file, doc, attr.getValueRegionStartOffset(), attr.getValueRegionText()
			.length(), attr.getValue(), problemType, severity, priority, message);
	}

	private void addMarker(ValidationResult result, IFile file, IStructuredDocument doc,
		int start, int length, String errorValue, String problemType, int severity, int priority,
		String message)
	{
		int lineNo = doc.getLineOfOffset(start) + 1;
		ValidatorMessage marker = ValidatorMessage.create(message, file);
		marker.setType(MARKER_ID);
		marker.setAttribute(IMarker.SEVERITY, severity);
		marker.setAttribute(IMarker.PRIORITY, priority);
		marker.setAttribute(IMarker.MESSAGE, message);
		marker.setAttribute(IMarker.LINE_NUMBER, lineNo);
		if (start != 0)
		{
			marker.setAttribute(IMarker.CHAR_START, start);
			marker.setAttribute(IMarker.CHAR_END, start + length);
		}
		// Adds custom attributes.
		marker.setAttribute("problemType", problemType);
		marker.setAttribute("errorValue", errorValue);
		result.add(marker);
	}

	private boolean isElementExists(IFile file, String xpath)
	{
		IStructuredModel model = null;
		try
		{
			model = StructuredModelManager.getModelManager().getModelForRead(file);
			IDOMModel domModel = (IDOMModel)model;
			IDOMDocument domDoc = domModel.getDocument();

			return XpathUtil.xpathBool(domDoc, xpath);
		}
		catch (Exception e)
		{
			Activator.log(Status.ERROR, "Error occurred during parsing mapper:" + file.getFullPath(),
				e);
		}
		finally
		{
			if (model != null)
			{
				model.releaseFromRead();
			}
		}
		return false;
	}
}
