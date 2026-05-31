package com.martinfou.trading.parser.sq;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

final class SqXmlDom {

    private SqXmlDom() {}

    static Document parse(InputStream xmlStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(xmlStream);
        } catch (Exception e) {
            throw new SqXmlParseException("Failed to parse SQ strategy XML", e);
        }
    }

    static Element requireChild(Element parent, String tagName) {
        Element child = firstChildElement(parent, tagName);
        if (child == null) {
            throw new SqXmlParseException("Missing <" + tagName + "> under <" + parent.getTagName() + ">");
        }
        return child;
    }

    static Element firstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el && tagName.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    static String textChild(Element parent, String tagName) {
        Element child = firstChildElement(parent, tagName);
        if (child == null) {
            return "";
        }
        return child.getTextContent().trim();
    }

    static Element requireRootStrategy(Element root) {
        if (!"StrategyFile".equals(root.getTagName())) {
            throw new SqXmlParseException("Expected root element StrategyFile, got: " + root.getTagName());
        }
        Element strategy = firstChildElement(root, "Strategy");
        if (strategy == null) {
            throw new SqXmlParseException("Missing Strategy element under StrategyFile");
        }
        return strategy;
    }
}
