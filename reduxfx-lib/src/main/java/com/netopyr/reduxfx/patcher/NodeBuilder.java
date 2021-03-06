package com.netopyr.reduxfx.patcher;

import com.netopyr.reduxfx.patcher.property.Accessor;
import com.netopyr.reduxfx.patcher.property.Accessors;
import com.netopyr.reduxfx.vscenegraph.VNode;
import com.netopyr.reduxfx.vscenegraph.event.VEventHandlerElement;
import com.netopyr.reduxfx.vscenegraph.event.VEventType;
import com.netopyr.reduxfx.vscenegraph.property.VProperty;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javaslang.collection.Map;
import javaslang.collection.Seq;
import javaslang.control.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import static com.netopyr.reduxfx.patcher.NodeUtilities.getChildren;

public class NodeBuilder<ACTION> {

    private static final Logger LOG = LoggerFactory.getLogger(NodeBuilder.class);

    private final Accessors<ACTION> accessors;
    private final Consumer<ACTION> dispatcher;

    NodeBuilder(Consumer<ACTION> dispatcher, Accessors<ACTION> accessors) {
        this.dispatcher = dispatcher;
        this.accessors = accessors;
    }

    @SuppressWarnings("unchecked")
    public Option<Node> create(VNode<ACTION> vNode) {
        try {
            final Class<? extends Node> nodeClass = vNode.getNodeClass();
            final Node node = nodeClass.newInstance();
            return Option.of(node);
        } catch (InstantiationException | IllegalAccessException e) {
            LOG.error("Unable to create node", e);
            return Option.none();
        }
    }

    @SuppressWarnings("unchecked")
    public void init(Node node, VNode vNode) {
        setProperties(node, vNode.getProperties().values());
        setEventHandlers(node, vNode.getEventHandlers());

        if (vNode.getChildren().nonEmpty()) {
            final Option<java.util.List<Node>> children = getChildren(node);
            if (children.isEmpty()) {
                LOG.error("VNode has children defined, but is neither a Group nor a Pane: {}", vNode);
                return;
            }

            vNode.getChildren().forEach(vChild -> {
                final Option<Node> child = create((VNode) vChild);
                if (child.isDefined()) {
                    children.get().add(child.get());
                    init(child.get(), (VNode) vChild);
                }
            });
        }


    }

    @SuppressWarnings("unchecked")
    void setProperties(Node node, Seq<VProperty<?, ACTION>> properties) {
        for (final VProperty<?, ACTION> vProperty : properties) {
            final Option<Accessor<?, ACTION>> accessor = accessors.getAccessor(node, vProperty.getName());
            if (accessor.isDefined()) {
                accessor.get().set(node, (VProperty) vProperty);
            } else {
                LOG.warn("Accessor not found for property {} in class {}", vProperty.getName(), node.getClass());
            }
        }
    }

    @SuppressWarnings("unchecked")
    void setEventHandlers(Node node, Map<VEventType, VEventHandlerElement<? extends Event, ACTION>> eventHandlers) {
        for (final VEventHandlerElement eventHandlerElement : eventHandlers.values()) {
            final Option<MethodHandle> setter = getEventSetter(node.getClass(), eventHandlerElement.getType());
            if (setter.isDefined()) {
                try {
                    final EventHandler<? extends Event> eventHandler = e -> {
                        final ACTION action = (ACTION) eventHandlerElement.getEventHandler().onChange(e);
                        if (action != null) {
                            dispatcher.accept(action);
                        }
                    };
                    setter.get().invoke(node, eventHandler);
                } catch (Throwable throwable) {
                    LOG.error("Unable to set JavaFX EventHandler " + eventHandlerElement.getType() + " for class " + node.getClass(), throwable);
                }
            }
        }
    }

    private static Option<MethodHandle> getEventSetter(Class<? extends Node> clazz, VEventType eventType) {
        // TODO: Cache the getter
        final String eventName = eventType.getName();
        final String setterName = "setOn" + eventName.substring(0, 1).toUpperCase() + eventName.substring(1);

        Method method = null;
        try {
            method = clazz.getMethod(setterName, EventHandler.class);
        } catch (NoSuchMethodException e) {
            // ignore
        }

        if (method == null) {
            LOG.error("Unable to find setter for EventHandler {} in class {}", eventName, clazz);
            return Option.none();
        }

        try {
            final MethodHandle methodHandle = MethodHandles.publicLookup().unreflect(method);
            return Option.of(methodHandle);
        } catch (IllegalAccessException e) {
            LOG.error("Setter for EventHandler {} in class {} is not accessible", eventName, clazz);
            return Option.none();
        }
    }
}
