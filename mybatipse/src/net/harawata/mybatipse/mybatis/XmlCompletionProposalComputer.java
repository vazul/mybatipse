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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.xpath.XPathExpressionException;

import net.harawata.mybatipse.Activator;
import net.harawata.mybatipse.bean.BeanPropertyCache;
import net.harawata.mybatipse.bean.BeanPropertyInfo;
import net.harawata.mybatipse.mybatis.JavaMapperUtil.MapperMethodInfo;
import net.harawata.mybatipse.util.XpathUtil;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.internal.core.PackageFragment;
import org.eclipse.jdt.ui.text.java.IJavaCompletionProposal;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.wst.sse.core.internal.provisional.text.IStructuredDocumentRegion;
import org.eclipse.wst.sse.core.internal.provisional.text.ITextRegion;
import org.eclipse.wst.sse.core.internal.provisional.text.ITextRegionList;
import org.eclipse.wst.sse.core.utils.StringUtils;
import org.eclipse.wst.sse.ui.contentassist.CompletionProposalInvocationContext;
import org.eclipse.wst.sse.ui.internal.contentassist.ContentAssistUtils;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMNode;
import org.eclipse.wst.xml.core.internal.regions.DOMRegionContext;
import org.eclipse.wst.xml.ui.internal.contentassist.ContentAssistRequest;
import org.eclipse.wst.xml.ui.internal.contentassist.DefaultXMLCompletionProposalComputer;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Iwao AVE!
 */
@SuppressWarnings("restriction")
public class XmlCompletionProposalComputer extends DefaultXMLCompletionProposalComputer
{
	enum ProposalType
	{
		None,
		MapperNamespace,
		ResultType,
		ResultProperty,
		StatementId,
		TypeHandlerType,
		CacheType,
		ResultMap,
		Include,
		Package,
		TypeAlias,
		SelectId,
		KeyProperty,
		ParamProperty,
		ParamPropertyPartial
	}

	@Override
	protected ContentAssistRequest computeCompletionProposals(String matchString,
		ITextRegion completionRegion, IDOMNode treeNode, IDOMNode xmlnode,
		CompletionProposalInvocationContext context)
	{
		ContentAssistRequest contentAssistRequest = super.computeCompletionProposals(matchString,
			completionRegion, treeNode, xmlnode, context);
		if (contentAssistRequest != null)
			return contentAssistRequest;

		String regionType = completionRegion.getType();
		if (DOMRegionContext.XML_CDATA_TEXT.equals(regionType))
		{
			Node parentNode = xmlnode.getParentNode();
			Node statementNode = MybatipseXmlUtil.findEnclosingStatementNode(parentNode);
			if (statementNode == null)
				return null;

			int offset = context.getInvocationOffset();
			ITextViewer viewer = context.getViewer();
			contentAssistRequest = new ContentAssistRequest(xmlnode, parentNode,
				ContentAssistUtils.getStructuredDocumentRegion(viewer, offset), completionRegion,
				offset, 0, matchString);
			proposeStatementText(contentAssistRequest, statementNode,
				MybatipseXmlUtil.findEnclosingForEachNode(parentNode));
		}
		return contentAssistRequest;
	}

	@Override
	protected void addTagInsertionProposals(ContentAssistRequest contentAssistRequest,
		int childPosition, CompletionProposalInvocationContext context)
	{
		int offset = contentAssistRequest.getReplacementBeginPosition();
		int length = contentAssistRequest.getReplacementLength();
		Node node = contentAssistRequest.getNode();
		// Current node can be 'parent' when the cursor is just before the end tag of the parent.
		Node parentNode = node.getNodeType() == Node.ELEMENT_NODE ? node : node.getParentNode();
		if (parentNode.getNodeType() != Node.ELEMENT_NODE)
			return;

		String tagName = parentNode.getNodeName();
		NamedNodeMap tagAttrs = parentNode.getAttributes();
		// Result mapping proposals.
		if ("resultMap".equals(tagName))
			generateResults(contentAssistRequest, offset, length, parentNode,
				tagAttrs.getNamedItem("type"));
		else if ("collection".equals(tagName))
			generateResults(contentAssistRequest, offset, length, parentNode,
				tagAttrs.getNamedItem("ofType"));
		else if ("association".equals(tagName))
			generateResults(contentAssistRequest, offset, length, parentNode,
				tagAttrs.getNamedItem("javaType"));

		Node statementNode = MybatipseXmlUtil.findEnclosingStatementNode(parentNode);
		if (statementNode == null)
			return;
		proposeStatementText(contentAssistRequest, statementNode,
			MybatipseXmlUtil.findEnclosingForEachNode(parentNode));
	}

	private void proposeStatementText(ContentAssistRequest contentAssistRequest,
		Node statementNode, Node forEachNode)
	{
		int offset = contentAssistRequest.getReplacementBeginPosition();
		String text = contentAssistRequest.getText();
		int offsetInText = offset - contentAssistRequest.getStartOffset() - 1;
		ExpressionProposalParser parser = new ExpressionProposalParser(text, offsetInText);
		if (parser.isProposable())
		{
			String matchString = parser.getMatchString();
			offset -= matchString.length();
			int length = parser.getReplacementLength();
			final IJavaProject project = getJavaProject(contentAssistRequest);
			String proposalTarget = parser.getProposalTarget();

			if (proposalTarget == null || proposalTarget.length() == 0)
				addProposals(contentAssistRequest,
					ProposalComputorHelper.proposeOptionName(offset, length, matchString));
			else if ("property".equals(proposalTarget))
				addProposals(
					contentAssistRequest,
					proposeParameter(project, offset, length, statementNode, forEachNode, true,
						matchString));
			else if ("jdbcType".equals(proposalTarget))
				addProposals(contentAssistRequest,
					ProposalComputorHelper.proposeJdbcType(offset, length, matchString));
			else if ("javaType".equals(proposalTarget))
				addProposals(contentAssistRequest,
					ProposalComputorHelper.proposeJavaType(project, offset, length, true, matchString));
			else if ("typeHandler".equals(proposalTarget))
				addProposals(contentAssistRequest,
					ProposalComputorHelper.proposeTypeHandler(project, offset, length, matchString));
		}
	}

	private List<ICompletionProposal> proposeParameter(IJavaProject project, final int offset,
		final int length, Node statementNode, Node forEachNode, final boolean searchReadable,
		final String matchString)
	{
		List<ICompletionProposal> proposals = new ArrayList<ICompletionProposal>();
		if (statementNode == null)
			return proposals;
		String statementId = null;
		String paramType = null;
		NamedNodeMap statementAttrs = statementNode.getAttributes();
		for (int i = 0; i < statementAttrs.getLength(); i++)
		{
			Node attr = statementAttrs.item(i);
			String attrName = attr.getNodeName();
			if ("id".equals(attrName))
				statementId = attr.getNodeValue();
			else if ("parameterType".equals(attrName))
				paramType = attr.getNodeValue();
		}
		if (statementId == null || statementId.length() == 0)
			return proposals;

		String forEachItem = null;
		String forEachCollection = null;
		if (forEachNode != null)
		{
			NamedNodeMap forEachAttrs = forEachNode.getAttributes();
			for (int i = 0; i < forEachAttrs.getLength(); i++)
			{
				Node attr = forEachAttrs.item(i);
				String attrName = attr.getNodeName();
				if ("item".equals(attrName))
					forEachItem = attr.getNodeValue();
				else if ("collection".equals(attrName))
					forEachCollection = attr.getNodeValue();
			}
		}

		if (paramType != null)
		{
			String resolved = TypeAliasCache.getInstance().resolveAlias(project, paramType, null);
			proposals = ProposalComputorHelper.proposePropertyFor(project, offset, length,
				resolved != null ? resolved : paramType, searchReadable, -1, matchString);
			if (forEachCollection != null && !forEachCollection.isEmpty())
			{
				// add item proposal
				String forEachCollectionType = getForEachType(project,
					Collections.singletonMap("value", paramType), forEachCollection);
				if (forEachCollectionType != null)
				{
					List<ICompletionProposal> forEachProposals = ProposalComputorHelper.proposeParameters(
						project, offset, length,
						Collections.singletonMap(forEachItem, forEachCollectionType), searchReadable,
						matchString, true);
					proposals.addAll(forEachProposals);
				}
			}
		}
		else
		{
			try
			{
				final List<MapperMethodInfo> methodInfos = new ArrayList<MapperMethodInfo>();
				String mapperFqn = MybatipseXmlUtil.getNamespace(statementNode.getOwnerDocument());
				JavaMapperUtil.findMapperMethod(methodInfos, project, mapperFqn, statementId, true,
					true);
				if (methodInfos.size() > 0)
				{
					proposals = ProposalComputorHelper.proposeParameters(project, offset, length,
						methodInfos.get(0).getParams(), searchReadable, matchString, false);
					if (forEachCollection != null && !forEachCollection.isEmpty())
					{
						String forEachCollectionType = getForEachType(project, methodInfos.get(0)
							.getParams(), forEachCollection);
						if (forEachCollectionType != null)
						{
							List<ICompletionProposal> forEachProposals = ProposalComputorHelper.proposeParameters(
								project, offset, length,
								Collections.singletonMap(forEachItem, forEachCollectionType), searchReadable,
								matchString, true);
							proposals.addAll(forEachProposals);
						}
					}
				}
			}
			catch (XPathExpressionException e)
			{
				Activator.log(Status.ERROR, e.getMessage(), e);
			}
		}
		return proposals;
	}

	private String getForEachType(IJavaProject project, Map<String, String> paramMap,
		String collectionProperty)
	{
		String collectionType = null;

		if (paramMap.size() == 1)
		{
			// If there is only one parameter with no @Param,
			// properties should be directly referenced.
			String paramType = paramMap.values().iterator().next();
			Map<String, String> fields = BeanPropertyCache.searchFields(project, paramType,
				collectionProperty, true, -1, true);
			// should be one field
			if (fields.size() == 1)
			{
				collectionType = fields.values().iterator().next();
			}

		}
		else if (paramMap.size() > 1)
		{
			int dotPos = collectionProperty.indexOf('.');
			if (dotPos == -1)
			{
				collectionType = paramMap.get(collectionProperty);
			}
			else
			{
				String paramName = collectionProperty.substring(0, dotPos);
				String qualifiedName = paramMap.get(paramName);
				if (qualifiedName != null)
				{
					String property = collectionProperty.substring(dotPos + 1);
					Map<String, String> fields = BeanPropertyCache.searchFields(project, qualifiedName,
						property, true, -1, true);
					// should be one field
					if (fields.size() == 1)
					{
						collectionType = fields.values().iterator().next();
					}
				}
			}
		}

		if (collectionType.endsWith("[]"))
			collectionType = collectionType.substring(0, collectionType.length() - 2);
		else if (collectionType.startsWith("java.util.List<"))
			collectionType = collectionType.substring(15, collectionType.length() - 1);
		return collectionType;
	}

	private void generateResults(ContentAssistRequest contentAssistRequest, int offset,
		int length, Node parentNode, Node typeAttr)
	{
		if (typeAttr == null)
			return;

		String typeValue = typeAttr.getNodeValue();
		if (typeValue == null || typeValue.length() == 0)
			return;

		IJavaProject project = getJavaProject(contentAssistRequest);
		// Try resolving the alias.
		String qualifiedName = TypeAliasCache.getInstance().resolveAlias(project, typeValue, null);
		if (qualifiedName == null)
		{
			// Assumed to be FQN.
			qualifiedName = typeValue;
		}
		BeanPropertyInfo beanProps = BeanPropertyCache.getBeanPropertyInfo(project, qualifiedName);
		try
		{
			Set<String> existingProps = new HashSet<String>();
			NodeList existingPropNodes = XpathUtil.xpathNodes(parentNode, "*[@property]/@property");
			for (int i = 0; i < existingPropNodes.getLength(); i++)
			{
				existingProps.add(existingPropNodes.item(i).getNodeValue());
			}
			StringBuilder resultTags = new StringBuilder();
			for (Entry<String, String> prop : beanProps.getWritableFields().entrySet())
			{
				String propName = prop.getKey();
				if (!existingProps.contains(propName))
				{
					resultTags.append("<result property=\"")
						.append(propName)
						.append("\" column=\"")
						.append(propName)
						.append("\" />\n");
				}
			}
			contentAssistRequest.addProposal(new CompletionProposal(resultTags.toString(), offset,
				length, resultTags.length(), Activator.getIcon(), "<result /> for properties", null,
				null));
		}
		catch (XPathExpressionException e)
		{
			Activator.log(Status.ERROR, e.getMessage(), e);
		}
	}

	protected void addAttributeValueProposals(ContentAssistRequest contentAssistRequest,
		CompletionProposalInvocationContext context)
	{
		IDOMNode node = (IDOMNode)contentAssistRequest.getNode();
		String tagName = node.getNodeName();
		IStructuredDocumentRegion open = node.getFirstStructuredDocumentRegion();
		ITextRegionList openRegions = open.getRegions();
		int i = openRegions.indexOf(contentAssistRequest.getRegion());
		if (i < 0)
			return;
		ITextRegion nameRegion = null;
		while (i >= 0)
		{
			nameRegion = openRegions.get(i--);
			if (nameRegion.getType() == DOMRegionContext.XML_TAG_ATTRIBUTE_NAME)
				break;
		}

		// get the attribute in question (first attr name to the left of the cursor)
		String attributeName = null;
		if (nameRegion != null)
			attributeName = open.getText(nameRegion);

		ProposalType proposalType = resolveProposalType(tagName, attributeName);
		if (ProposalType.None.equals(proposalType))
		{
			return;
		}

		String currentValue = null;
		if (contentAssistRequest.getRegion().getType() == DOMRegionContext.XML_TAG_ATTRIBUTE_VALUE)
			currentValue = contentAssistRequest.getText();
		else
			currentValue = "";

		String matchString = null;
		int matchStrLen = contentAssistRequest.getMatchString().length();
		int start = contentAssistRequest.getReplacementBeginPosition();
		int length = contentAssistRequest.getReplacementLength();
		if (currentValue.length() > StringUtils.strip(currentValue).length()
			&& (currentValue.startsWith("\"") || currentValue.startsWith("'")) && matchStrLen > 0)
		{
			// Value is surrounded by (double) quotes.
			matchString = currentValue.substring(1, matchStrLen);
			start++;
			length = currentValue.length() - 2;
			currentValue = currentValue.substring(1, length + 1);
		}
		else
		{
			matchString = currentValue.substring(0, matchStrLen);
		}

		IJavaProject project = getJavaProject(contentAssistRequest);
		try
		{
			switch (proposalType)
			{
				case Package:
					proposePackage(contentAssistRequest, project, matchString, start, length);
					break;
				case TypeAlias:
					addProposals(contentAssistRequest,
						ProposalComputorHelper.proposeJavaType(project, start, length, false, matchString));
					break;
				case ResultType:
					addProposals(contentAssistRequest,
						ProposalComputorHelper.proposeJavaType(project, start, length, true, matchString));
					break;
				case ResultProperty:
					proposeProperty(contentAssistRequest, matchString, start, length, node);
					break;
				case TypeHandlerType:
					addProposals(contentAssistRequest,
						ProposalComputorHelper.proposeTypeHandler(project, start, length, matchString));
					break;
				case CacheType:
					addProposals(contentAssistRequest,
						ProposalComputorHelper.proposeCacheType(project, start, length, matchString));
					break;
				case StatementId:
					proposeStatementId(contentAssistRequest, project, matchString, start, length, node);
					break;
				case MapperNamespace:
					proposeMapperNamespace(contentAssistRequest, project, start, length);
					break;
				case ResultMap:
					// TODO: Exclude the current resultMap id when proposing 'extends'
					addProposals(
						contentAssistRequest,
						proposeResultMapReference(project, node.getOwnerDocument(), start, currentValue,
							matchString.length()));
					break;
				case Include:
					addProposals(contentAssistRequest, ProposalComputorHelper.proposeReference(project,
						node.getOwnerDocument(), matchString, start, length, "sql"));
					break;
				case SelectId:
					// TODO: include mapper methods with @Select.
					addProposals(contentAssistRequest, ProposalComputorHelper.proposeReference(project,
						node.getOwnerDocument(), matchString, start, length, "select"));
					break;
				case KeyProperty:
					String nodeName = node.getNodeName();
					Node statementNode = "update".equals(nodeName) || "insert".equals(nodeName) ? node
						: MybatipseXmlUtil.findEnclosingStatementNode(node.getParentNode());
					addProposals(contentAssistRequest,
						proposeParameter(project, start, length, statementNode, null, false, matchString));
					break;
				case ParamProperty:
					addProposals(
						contentAssistRequest,
						proposeParameter(project, start, length,
							MybatipseXmlUtil.findEnclosingStatementNode(node), null, true, matchString));
					break;
				case ParamPropertyPartial:
					AttrTextParser parser = new AttrTextParser(currentValue, matchString.length());
					addProposals(
						contentAssistRequest,
						proposeParameter(project, start + parser.getMatchStringStart(),
							parser.getReplacementLength(),
							MybatipseXmlUtil.findEnclosingStatementNode(node.getParentNode()), null, true,
							parser.getMatchString()));
					break;
				default:
					break;
			}
		}
		catch (Exception e)
		{
			Activator.log(Status.ERROR, e.getMessage(), e);
		}
	}

	private List<ICompletionProposal> proposeResultMapReference(IJavaProject project,
		Document domDoc, int start, String currentValue, int offsetInCurrentValue)
		throws XPathExpressionException, IOException, CoreException
	{
		int leftComma = currentValue.lastIndexOf(',', offsetInCurrentValue);
		int rightComma = currentValue.indexOf(',', offsetInCurrentValue);
		String newMatchString = currentValue.substring(leftComma + 1, offsetInCurrentValue).trim();
		int newStart = start + offsetInCurrentValue - newMatchString.length();
		int newLength = currentValue.length() - (offsetInCurrentValue - newMatchString.length())
			- (rightComma > -1 ? currentValue.length() - rightComma : 0);
		return ProposalComputorHelper.proposeReference(project, domDoc, newMatchString, newStart,
			newLength, "resultMap");
	}

	private void proposeMapperNamespace(ContentAssistRequest contentAssistRequest,
		IJavaProject project, int start, int length)
	{
		// Calculate namespace from the file's classpath.
		String namespace = MybatipseXmlUtil.getJavaMapperType(project);
		ICompletionProposal proposal = new CompletionProposal(namespace, start, length,
			namespace.length(), Activator.getIcon("/icons/mybatis-ns.png"), namespace, null, null);
		contentAssistRequest.addProposal(proposal);
	}

	private void proposeStatementId(ContentAssistRequest contentAssistRequest,
		IJavaProject project, String matchString, int start, int length, IDOMNode node)
		throws JavaModelException, XPathExpressionException
	{
		final List<ICompletionProposal> results = new ArrayList<ICompletionProposal>();
		final List<MapperMethodInfo> methodInfos = new ArrayList<MapperMethodInfo>();
		String qualifiedName = MybatipseXmlUtil.getNamespace(node.getOwnerDocument());
		JavaMapperUtil.findMapperMethod(methodInfos, project, qualifiedName, matchString, false,
			true);
		for (MapperMethodInfo methodInfo : methodInfos)
		{
			String methodName = methodInfo.getMethodName();
			results.add(new CompletionProposal(methodName, start, length, methodName.length(),
				Activator.getIcon(), methodName, null, null));
		}
		addProposals(contentAssistRequest, results);
	}

	private void proposeProperty(ContentAssistRequest contentAssistRequest, String matchString,
		int start, int length, IDOMNode node) throws JavaModelException
	{
		String javaType = MybatipseXmlUtil.findEnclosingType(node);
		if (javaType != null && !MybatipseXmlUtil.isDefaultTypeAlias(javaType))
		{
			IJavaProject project = getJavaProject(contentAssistRequest);
			IType type = project.findType(javaType);
			if (type == null)
			{
				javaType = TypeAliasCache.getInstance().resolveAlias(project, javaType, null);
				if (javaType == null)
					return;
			}
			addProposals(contentAssistRequest, ProposalComputorHelper.proposePropertyFor(project,
				start, length, javaType, false, -1, matchString));
		}
	}

	private void proposePackage(final ContentAssistRequest contentAssistRequest,
		IJavaProject project, String matchString, final int start, final int length)
		throws CoreException
	{
		final List<ICompletionProposal> results = new ArrayList<ICompletionProposal>();
		final Set<String> foundPkgs = new HashSet<String>();
		int includeMask = IJavaSearchScope.SOURCES | IJavaSearchScope.REFERENCED_PROJECTS;
		// Include application libraries only when package is specified (for better performance).
		boolean pkgSpecified = matchString != null && matchString.indexOf('.') > 0;
		if (pkgSpecified)
			includeMask |= IJavaSearchScope.APPLICATION_LIBRARIES | IJavaSearchScope.SYSTEM_LIBRARIES;
		IJavaSearchScope scope = SearchEngine.createJavaSearchScope(new IJavaProject[]{
			project
		}, includeMask);
		SearchRequestor requestor = new SearchRequestor()
		{
			@Override
			public void acceptSearchMatch(SearchMatch match) throws CoreException
			{
				PackageFragment element = (PackageFragment)match.getElement();
				String pkg = element.getElementName();
				if (pkg != null && pkg.length() > 0 && !foundPkgs.contains(pkg))
				{
					foundPkgs.add(pkg);
					results.add(new CompletionProposal(pkg, start, length, pkg.length(),
						Activator.getIcon(), pkg, null, null));
				}
			}
		};
		searchPackage(matchString, scope, requestor);
		addProposals(contentAssistRequest, results);
	}

	private void searchPackage(String matchString, IJavaSearchScope scope,
		SearchRequestor requestor) throws CoreException
	{
		SearchPattern pattern = SearchPattern.createPattern(matchString + "*",
			IJavaSearchConstants.PACKAGE, IJavaSearchConstants.DECLARATIONS,
			SearchPattern.R_PREFIX_MATCH);
		SearchEngine searchEngine = new SearchEngine();
		searchEngine.search(pattern, new SearchParticipant[]{
			SearchEngine.getDefaultSearchParticipant()
		}, scope, requestor, null);
	}

	private void addProposals(final ContentAssistRequest contentAssistRequest,
		List<ICompletionProposal> proposals)
	{
		Collections.sort(proposals, new CompletionProposalComparator());
		for (ICompletionProposal proposal : proposals)
		{
			contentAssistRequest.addProposal(proposal);
		}
	}

	private ProposalType resolveProposalType(String tag, String attr)
	{
		// TODO: proxyFactory, logImpl
		if ("mapper".equals(tag) && "namespace".equals(attr))
			return ProposalType.MapperNamespace;
		else if ("type".equals(attr) && "typeAlias".equals(tag))
			return ProposalType.TypeAlias;
		else if ("type".equals(attr) && "cache".equals(tag))
			return ProposalType.CacheType;
		else if ("type".equals(attr) && "objectFactory".equals(tag))
			return ProposalType.None; // TODO propose object factory
		else if ("type".equals(attr) && "objectWrapperFactory".equals(tag))
			return ProposalType.None; // TODO propose object wrapper factory
		else if ("type".equals(attr) || "resultType".equals(attr) || "parameterType".equals(attr)
			|| "ofType".equals(attr) || "javaType".equals(attr))
			return ProposalType.ResultType;
		else if ("property".equals(attr))
			return ProposalType.ResultProperty;
		else if ("package".equals(tag) && "name".equals(attr))
			return ProposalType.Package;
		else if ("typeHandler".equals(attr) || "handler".equals(attr))
			return ProposalType.TypeHandlerType;
		else if ("resultMap".equals(attr) || "extends".equals(attr))
			return ProposalType.ResultMap;
		else if ("refid".equals(attr))
			return ProposalType.Include;
		else if ("select".equals(attr))
			return ProposalType.SelectId;
		else if ("keyProperty".equals(attr))
			return ProposalType.KeyProperty;
		else if ("collection".equals(attr))
			return ProposalType.ParamProperty;
		else if ("test".equals(attr) || ("bind".equals(tag) && "value".equals(attr)))
			return ProposalType.ParamPropertyPartial;
		else if ("id".equals(attr)
			&& ("select".equals(tag) || "update".equals(tag) || "insert".equals(tag) || "delete".equals(tag)))
			return ProposalType.StatementId;
		return ProposalType.None;
	}

	private IJavaProject getJavaProject(ContentAssistRequest request)
	{
		if (request != null)
		{
			IStructuredDocumentRegion region = request.getDocumentRegion();
			if (region != null)
			{
				IDocument document = region.getParentDocument();
				return MybatipseXmlUtil.getJavaProject(document);
			}
		}
		return null;
	}

	private class CompletionProposalComparator implements Comparator<ICompletionProposal>
	{
		@Override
		public int compare(ICompletionProposal p1, ICompletionProposal p2)
		{
			if (p1 instanceof IJavaCompletionProposal && p2 instanceof IJavaCompletionProposal)
			{
				int relevance1 = ((IJavaCompletionProposal)p1).getRelevance();
				int relevance2 = ((IJavaCompletionProposal)p2).getRelevance();
				int diff = relevance2 - relevance1;
				if (diff != 0)
					return diff;
			}
			return p1.getDisplayString().compareToIgnoreCase(p2.getDisplayString());
		}
	}

	private class AttrTextParser
	{
		private String text;

		private int offset;

		private String matchString;

		public AttrTextParser(String text, int offset)
		{
			super();
			this.text = text;
			this.offset = offset;
			parse();
		}

		private void parse()
		{
			for (int i = offset - 1; i > 0; i--)
			{
				char c = text.charAt(i);
				if (!(Character.isJavaIdentifierPart(c) || c == '[' || c == ']' || c == '.'))
				{
					matchString = text.substring(i + 1, offset);
					return;
				}
			}
			matchString = text.substring(0, offset);
		}

		public int getMatchStringStart()
		{
			return offset - matchString.length();
		}

		public int getReplacementLength()
		{
			int i = offset;
			for (; i < text.length(); i++)
			{
				char c = text.charAt(i);
				if (!(Character.isJavaIdentifierPart(c) || c == '[' || c == ']' || c == '.'))
				{
					break;
				}
			}
			return i - offset + matchString.length();
		}

		public String getMatchString()
		{
			return matchString;
		}
	}
}
