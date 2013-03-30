package de.enerko.aaw2.app.vaadin;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.core.Authentication;
import org.springframework.security.util.SimpleMethodInvocation;

import com.vaadin.navigator.Navigator;
import com.vaadin.navigator.View;
import com.vaadin.navigator.ViewDisplay;
import com.vaadin.navigator.ViewProvider;
import com.vaadin.ui.UI;

/**
 * This is a specialized Navigator that takes care of evaluating {@link ViewDescription} 
 * especially {@link ViewDescription#requiredPermissions()}. Those expressions are evaluated
 * within the current Spring Security Context. If they evaluate to false, the view in concern
 * is not even instantiated.
 * 
 * The navigator then uses the SpringViewProvider that finally instantiates all views.
 * 
 * This navigator doesn't and shouldn't support adding and removing views.
 * 
 * @author Michael J. Simons, 2013-03-04
 */
public class SpringSecurityNavigator extends Navigator {
	private static final long serialVersionUID = -7896790310309542217L;

	/**
	 * View provider that pulls views from the application context and optionally caches them.
	 */
	@Configurable
	private class SpringViewProvider implements ViewProvider {
		private static final long serialVersionUID = -8555986824827085073L;
		/** Will be injected through AspectJ upon new and deserialization */
		@Autowired
		transient ApplicationContext applicationContext;
		/** The viewname -> view class mapping */
		final Map<String, Class<? extends View>> views = new HashMap<>();
		/** Cached instances of views */
		final Map<String, View> cachedInstances = new HashMap<>();

		@Override
		public String getViewName(String viewAndParameters) {
			String rv = null;
			if(viewAndParameters != null) {
				if(views.containsKey(viewAndParameters))
					rv = viewAndParameters;
				else {
					for(String viewName : views.keySet()) {
						if(viewAndParameters.startsWith(viewName + "/")) {
							rv = viewName;
							break;
						}
					}
				}
			}
			return rv;
		}

		@Override
		public View getView(String viewName) {			
			View rv = null;
			
			// Retrieve the implementing class
			Class<? extends View> clazz = this.views.get(viewName);
			// Try to find cached instance of caching is enabled and the view is cacheable
			if(isCachingEnabled() && clazz.getAnnotation(ViewDescription.class).cacheable()) {
				rv = this.cachedInstances.get(viewName);
				// retrieve the new instance and cache it if it's not already cached.
				if(rv == null)
					this.cachedInstances.put(viewName, rv = applicationContext.getBean(clazz));
			} else {
				rv = applicationContext.getBean(clazz);
			}
			return rv;
		}		
		
		/**
		 * Caching should be enabled only in "prod" environment
		 * @return true if caching is enabled
		 */
		boolean isCachingEnabled() {
			return Arrays.asList(this.applicationContext.getEnvironment().getActiveProfiles()).contains("prod");
		}
	}

	@SuppressWarnings("unchecked")
	public SpringSecurityNavigator(
			final ApplicationContext applicationContext,
			final Authentication authentication,
			final UI ui, 
			final ViewDisplay display
	) {
		super(ui, display);

		try {
			final SpringViewProvider springViewProvider = new SpringViewProvider();		
	
			// Retrieve the default SecurityExpressionHandler 
			final MethodSecurityExpressionHandler securityExpressionHandler = applicationContext.getBean(DefaultMethodSecurityExpressionHandler.class);
			// The method that is protected in the end
			final Method getViewMethod = SpringViewProvider.class.getMethod("getView", String.class);
			// A parser to evaluate parse the permissions.
			final SpelExpressionParser parser = new SpelExpressionParser();
	
			// Although beans can be retrieved by annotation they must be retrieved by name
			// to avoid instanciating them
			for(String beanName : applicationContext.getBeanDefinitionNames()) {
				final Class<?> beanClass = applicationContext.getType(beanName);
				// only work with Views that are described by our specialed Description
				if (beanClass.isAnnotationPresent(ViewDescription.class) && View.class.isAssignableFrom(beanClass)) {
					final ViewDescription viewDescription = beanClass.getAnnotation(ViewDescription.class);
					// requires no special permissions and can be immediatly added
					if(StringUtils.isBlank(viewDescription.requiredPermissions())) {
						springViewProvider.views.put(viewDescription.name(), (Class<? extends View>) beanClass);
					} 
					// requires permissions
					else {
						// this is actually borrowed from the code in org.springframework.security.access.prepost.PreAuthorize
						final EvaluationContext evaluationContext = securityExpressionHandler.createEvaluationContext(authentication, new SimpleMethodInvocation(springViewProvider, getViewMethod, viewDescription.name()));
						// only add the view to my provider if the permissions evaluate to true
						if(ExpressionUtils.evaluateAsBoolean(parser.parseExpression(viewDescription.requiredPermissions()), evaluationContext))
							springViewProvider.views.put(viewDescription.name(), (Class<? extends View>) beanClass);							
					}
				}        
	        }			
			super.addProvider(springViewProvider);
		} catch (NoSuchMethodException | SecurityException e) {
			// Won't happen
		} 
	}

	@Override
	public void addView(String viewName, View view) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void addView(String viewName, Class<? extends View> viewClass) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void addProvider(ViewProvider provider) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void removeView(String viewName) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void removeProvider(ViewProvider provider) {
		throw new UnsupportedOperationException();
	}
}