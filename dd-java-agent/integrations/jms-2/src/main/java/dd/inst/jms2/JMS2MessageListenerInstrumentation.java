package dd.inst.jms2;

import static dd.inst.jms.util.JmsUtil.toResourceName;
import static dd.trace.ClassLoaderMatcher.classLoaderHasClasses;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.datadoghq.trace.DDTags;
import com.google.auto.service.AutoService;
import dd.inst.jms.util.MessagePropertyTextMap;
import dd.trace.DDAdvice;
import dd.trace.Instrumenter;
import io.opentracing.ActiveSpan;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import javax.jms.Message;
import javax.jms.MessageListener;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class JMS2MessageListenerInstrumentation implements Instrumenter {

  @Override
  public AgentBuilder instrument(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            not(isInterface()).and(hasSuperType(named("javax.jms.MessageListener"))),
            classLoaderHasClasses("javax.jms.JMSContext", "javax.jms.CompletionListener"))
        .transform(JMS2MessageConsumerInstrumentation.JMS2_HELPER_INJECTOR)
        .transform(
            DDAdvice.create()
                .advice(
                    named("onMessage")
                        .and(takesArgument(0, named("javax.jms.Message")))
                        .and(isPublic()),
                    MessageListenerAdvice.class.getName()))
        .asDecorator();
  }

  public static class MessageListenerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ActiveSpan startSpan(
        @Advice.Argument(0) final Message message, @Advice.This final MessageListener listener) {

      final SpanContext extractedContext =
          GlobalTracer.get().extract(Format.Builtin.TEXT_MAP, new MessagePropertyTextMap(message));

      final ActiveSpan span =
          GlobalTracer.get()
              .buildSpan("jms.onMessage")
              .asChildOf(extractedContext)
              .withTag(DDTags.SERVICE_NAME, "jms")
              .withTag(DDTags.RESOURCE_NAME, "Received from " + toResourceName(message, null))
              .withTag(Tags.COMPONENT.getKey(), "jms2")
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CONSUMER)
              .withTag("span.origin.type", listener.getClass().getName())
              .startActive();

      return span;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final ActiveSpan span, @Advice.Thrown final Throwable throwable) {

      if (span != null) {
        if (throwable != null) {
          Tags.ERROR.set(span, Boolean.TRUE);
          span.log(Collections.singletonMap("error.object", throwable));
        }
        span.deactivate();
      }
    }
  }
}
