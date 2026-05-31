package com.martinfou.trading.parser.sq;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses StrategyQuant {@code StrategyFile} XML into {@link SqStrategyDocument}.
 *
 * @see docs/sq-xml-format.md
 */
public final class SqXmlParser {

    private SqXmlParser() {}

    public static SqStrategyDocument parse(Path xmlPath) throws IOException {
        try (InputStream in = Files.newInputStream(xmlPath)) {
            return parse(in);
        } catch (SqXmlParseException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse SQ XML: " + xmlPath, e);
        }
    }

    public static SqStrategyDocument parse(InputStream xmlStream) {
        var doc = SqXmlDom.parse(xmlStream);
        Element root = doc.getDocumentElement();
        Element strategy = SqXmlDom.requireRootStrategy(root);

        return new SqStrategyDocument(
            root.getAttribute("Version"),
            strategy.getAttribute("name"),
            strategy.getAttribute("engine"),
            readMoneyManagement(strategy),
            readGlobalSlPt(strategy),
            readVariables(strategy),
            readEvents(strategy)
        );
    }

    private static SqMoneyManagement readMoneyManagement(Element strategy) {
        Element mm = SqXmlDom.firstChildElement(strategy, "MoneyManagement");
        if (mm == null) {
            return new SqMoneyManagement("", Map.of());
        }
        Map<String, String> params = new LinkedHashMap<>();
        Element paramsEl = SqXmlDom.firstChildElement(mm, "params");
        if (paramsEl != null) {
            collectParams(paramsEl, params);
        }
        return new SqMoneyManagement(mm.getAttribute("type"), Map.copyOf(params));
    }

    private static void collectParams(Element parent, Map<String, String> sink) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && "Param".equals(el.getTagName())) {
                sink.put(el.getAttribute("key"), el.getTextContent().trim());
            }
        }
    }

    private static SqGlobalSlPt readGlobalSlPt(Element strategy) {
        Element gs = SqXmlDom.firstChildElement(strategy, "GlobalSLPT");
        if (gs == null) {
            return new SqGlobalSlPt(true, 0, 0);
        }
        boolean same = Boolean.parseBoolean(SqXmlDom.textChild(gs, "useSameSLPTforBothDirections"));
        int sl = readFixedValue(gs, "globalSL");
        int pt = readFixedValue(gs, "globalPT");
        return new SqGlobalSlPt(same, sl, pt);
    }

    private static int readFixedValue(Element globalSlPt, String tag) {
        Element wrapper = SqXmlDom.firstChildElement(globalSlPt, "values");
        if (wrapper == null) {
            return 0;
        }
        Element section = SqXmlDom.firstChildElement(wrapper, tag);
        if (section == null) {
            return 0;
        }
        Element values = SqXmlDom.firstChildElement(section, "values");
        if (values == null) {
            return 0;
        }
        String text = SqXmlDom.textChild(values, "value");
        if (text.isEmpty()) {
            return 0;
        }
        return Integer.parseInt(text);
    }

    private static List<SqXmlVariable> readVariables(Element strategy) {
        Element variablesEl = SqXmlDom.firstChildElement(strategy, "Variables");
        if (variablesEl == null) {
            return List.of();
        }
        List<SqXmlVariable> out = new ArrayList<>();
        NodeList children = variablesEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element el) || !"variable".equals(el.getTagName())) {
                continue;
            }
            out.add(new SqXmlVariable(
                SqXmlDom.textChild(el, "id"),
                SqXmlDom.textChild(el, "name"),
                SqXmlDom.textChild(el, "type"),
                SqXmlDom.textChild(el, "value"),
                SqXmlDom.textChild(el, "paramType")
            ));
        }
        return List.copyOf(out);
    }

    private static List<SqXmlEvent> readEvents(Element strategy) {
        Element rules = SqXmlDom.firstChildElement(strategy, "Rules");
        if (rules == null) {
            return List.of();
        }
        Element eventsEl = SqXmlDom.firstChildElement(rules, "Events");
        if (eventsEl == null) {
            return List.of();
        }
        List<SqXmlEvent> out = new ArrayList<>();
        NodeList children = eventsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element eventEl) || !"Event".equals(eventEl.getTagName())) {
                continue;
            }
            out.add(new SqXmlEvent(eventEl.getAttribute("key"), readRules(eventEl)));
        }
        return List.copyOf(out);
    }

    private static List<SqXmlRule> readRules(Element eventEl) {
        List<SqXmlRule> out = new ArrayList<>();
        NodeList children = eventEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element ruleEl) || !"Rule".equals(ruleEl.getTagName())) {
                continue;
            }
            out.add(readRule(ruleEl));
        }
        return List.copyOf(out);
    }

    private static SqXmlRule readRule(Element ruleEl) {
        String type = ruleEl.getAttribute("type");
        String name = ruleEl.getAttribute("name");
        if ("Signal".equals(type)) {
            return new SqXmlRule(name, type, readSignals(ruleEl), Optional.empty(), List.of());
        }
        Optional<SqXmlItem> condition = Optional.empty();
        List<SqXmlItem> actions = new ArrayList<>();
        Element ifEl = SqXmlDom.firstChildElement(ruleEl, "If");
        if (ifEl != null) {
            condition = firstItem(ifEl);
        }
        Element thenEl = SqXmlDom.firstChildElement(ruleEl, "Then");
        if (thenEl != null) {
            actions = allDirectItems(thenEl);
        }
        return new SqXmlRule(name, type, List.of(), condition, List.copyOf(actions));
    }

    private static List<SqXmlSignal> readSignals(Element ruleEl) {
        Element signalsEl = SqXmlDom.firstChildElement(ruleEl, "signals");
        if (signalsEl == null) {
            return List.of();
        }
        List<SqXmlSignal> out = new ArrayList<>();
        NodeList children = signalsEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element signalEl) || !"signal".equals(signalEl.getTagName())) {
                continue;
            }
            String variableId = signalEl.getAttribute("variable");
            SqXmlItem item = firstItem(signalEl).orElseThrow(() ->
                new SqXmlParseException("Signal missing Item for variable " + variableId));
            out.add(new SqXmlSignal(variableId, item));
        }
        return List.copyOf(out);
    }

    private static Optional<SqXmlItem> firstItem(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && "Item".equals(el.getTagName())) {
                return Optional.of(readItem(el));
            }
        }
        return Optional.empty();
    }

    private static List<SqXmlItem> allDirectItems(Element parent) {
        List<SqXmlItem> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && "Item".equals(el.getTagName())) {
                out.add(readItem(el));
            }
        }
        return List.copyOf(out);
    }

    static SqXmlItem readItem(Element itemEl) {
        List<SqXmlParam> params = readParams(itemEl);
        List<SqXmlBlock> blocks = readBlocks(itemEl);
        return new SqXmlItem(
            itemEl.getAttribute("key"),
            itemEl.getAttribute("name"),
            itemEl.getAttribute("display"),
            itemEl.getAttribute("categoryType"),
            itemEl.getAttribute("returnType"),
            params,
            blocks
        );
    }

    private static List<SqXmlParam> readParams(Element itemEl) {
        List<SqXmlParam> out = new ArrayList<>();
        NodeList children = itemEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element paramEl) || !"Param".equals(paramEl.getTagName())) {
                continue;
            }
            out.add(readParam(paramEl));
        }
        return List.copyOf(out);
    }

    private static SqXmlParam readParam(Element paramEl) {
        String key = paramEl.getAttribute("key");
        String type = paramEl.getAttribute("type");
        boolean variableRef = "true".equals(paramEl.getAttribute("variable"));
        SqXmlItem formulaRoot = readFormulaItem(paramEl);
        String text = formulaRoot == null ? paramEl.getTextContent().trim() : "";
        return new SqXmlParam(key, type, text, variableRef, formulaRoot);
    }

    private static SqXmlItem readFormulaItem(Element paramEl) {
        Element formula = SqXmlDom.firstChildElement(paramEl, "Formula");
        if (formula == null) {
            return null;
        }
        Element block = SqXmlDom.firstChildElement(formula, "Block");
        if (block == null) {
            return null;
        }
        return firstItem(block).orElse(null);
    }

    private static List<SqXmlBlock> readBlocks(Element itemEl) {
        List<SqXmlBlock> out = new ArrayList<>();
        NodeList children = itemEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element blockEl) || !"Block".equals(blockEl.getTagName())) {
                continue;
            }
            String blockKey = blockEl.getAttribute("key");
            SqXmlItem nested = firstItem(blockEl).orElse(null);
            if (nested != null) {
                out.add(new SqXmlBlock(blockKey, nested));
            }
        }
        return List.copyOf(out);
    }
}
