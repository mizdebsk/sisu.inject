/*******************************************************************************
 * Copyright (c) 2010, 2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Stuart McCulloch (Sonatype, Inc.) - initial API and implementation
 *******************************************************************************/
package org.eclipse.sisu.wire;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.sisu.Parameters;
import org.eclipse.sisu.inject.Logs;
import org.eclipse.sisu.inject.RankingFunction;
import org.eclipse.sisu.inject.TypeParameters;

import com.google.inject.Binder;
import com.google.inject.Binding;
import com.google.inject.Key;
import com.google.inject.PrivateBinder;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.DefaultElementVisitor;
import com.google.inject.spi.Element;
import com.google.inject.spi.ElementVisitor;
import com.google.inject.spi.InjectionRequest;
import com.google.inject.spi.InstanceBinding;
import com.google.inject.spi.PrivateElements;
import com.google.inject.spi.ProviderInstanceBinding;
import com.google.inject.spi.ProviderLookup;
import com.google.inject.spi.StaticInjectionRequest;

/**
 * {@link ElementVisitor} that analyzes linked {@link Binding}s for non-local injection dependencies.
 */
final class ElementAnalyzer
    extends DefaultElementVisitor<Void>
{
    // ----------------------------------------------------------------------
    // Static initialization
    // ----------------------------------------------------------------------

    static
    {
        Key<? extends RankingFunction> legacyRankingKey = null;
        try
        {
            @SuppressWarnings( "unchecked" )
            final Class<? extends RankingFunction> legacyType = (Class<? extends RankingFunction>) //
                RankingFunction.class.getClassLoader().loadClass( "org.sonatype.guice.bean.locators.RankingFunction" );
            if ( RankingFunction.class.isAssignableFrom( legacyType ) )
            {
                legacyRankingKey = Key.get( legacyType );
            }
        }
        catch ( final Throwable e )
        {
            Logs.catchThrowable( e );
        }
        LEGACY_RANKING_KEY = legacyRankingKey;
    }

    // ----------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------

    private static final Key<RankingFunction> RANKING_KEY = Key.get( RankingFunction.class );

    private static final Key<? extends RankingFunction> LEGACY_RANKING_KEY;

    // ----------------------------------------------------------------------
    // Implementation fields
    // ----------------------------------------------------------------------

    private final Set<Key<?>> localKeys = new HashSet<Key<?>>();

    private final DependencyAnalyzer analyzer = new DependencyAnalyzer();

    private final List<ElementAnalyzer> privateAnalyzers = new ArrayList<ElementAnalyzer>();

    private final List<Map<?, ?>> properties = new ArrayList<Map<?, ?>>();

    private final List<String> arguments = new ArrayList<String>();

    private final Binder binder;

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------

    ElementAnalyzer( final Binder binder )
    {
        this.binder = binder;
    }

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    public void ignoreKeys( final Set<Key<?>> keys )
    {
        localKeys.addAll( keys );
    }

    public void apply( final Wiring wiring )
    {
        // calculate which dependencies are missing from the module elements
        final Set<Key<?>> missingKeys = analyzer.findMissingKeys( localKeys );
        final Map<?, ?> mergedProperties = new MergedProperties( properties );
        for ( final Key<?> key : missingKeys )
        {
            if ( isParameters( key.getAnnotationType() ) )
            {
                wireParameters( key, mergedProperties );
            }
            else
            {
                wiring.wire( key );
            }
        }

        for ( final ElementAnalyzer privateAnalyzer : privateAnalyzers )
        {
            // ignore parent local/wired dependencies
            privateAnalyzer.ignoreKeys( localKeys );
            privateAnalyzer.ignoreKeys( missingKeys );
            privateAnalyzer.apply( wiring );
        }
    }

    @Override
    public <T> Void visit( final Binding<T> binding )
    {
        final Key<T> key = binding.getKey();
        if ( !localKeys.contains( key ) )
        {
            if ( isParameters( key.getAnnotationType() ) )
            {
                mergeParameters( binding );
            }
            else if ( binding.acceptTargetVisitor( analyzer ).booleanValue() )
            {
                localKeys.add( key );
                binding.applyTo( binder );

                if ( null != LEGACY_RANKING_KEY )
                {
                    // respect legacy ranking function overrides by using a binding alias
                    if ( key.equals( LEGACY_RANKING_KEY ) && localKeys.add( RANKING_KEY ) )
                    {
                        binder.bind( RANKING_KEY ).to( LEGACY_RANKING_KEY );
                    }
                }
            }
            else
            {
                Logs.trace( "Discard binding: {}", binding, null );
            }
        }
        return null;
    }

    @Override
    public Void visit( final PrivateElements elements )
    {
        // Follows example set by Guice Modules when rewriting private elements:
        //
        // 1. create new private binder, using the elements source token
        // 2. for all elements, apply each element to the private binder
        // 3. re-expose any exposed keys using their exposed source token

        final PrivateBinder privateBinder = binder.withSource( elements.getSource() ).newPrivateBinder();
        final ElementAnalyzer privateAnalyzer = new ElementAnalyzer( privateBinder );

        privateAnalyzers.add( privateAnalyzer );

        // ignore bindings already in the parent
        privateAnalyzer.ignoreKeys( localKeys );
        for ( final Element e : elements.getElements() )
        {
            e.acceptVisitor( privateAnalyzer );
        }

        for ( final Key<?> k : elements.getExposedKeys() )
        {
            // only expose valid bindings that won't conflict with existing ones
            if ( privateAnalyzer.localKeys.contains( k ) && localKeys.add( k ) )
            {
                privateBinder.withSource( elements.getExposedSource( k ) ).expose( k );
            }
        }

        return null;
    }

    @Override
    public <T> Void visit( final ProviderLookup<T> lookup )
    {
        analyzer.visit( lookup );
        lookup.applyTo( binder );
        return null;
    }

    @Override
    public Void visit( final StaticInjectionRequest request )
    {
        analyzer.visit( request );
        request.applyTo( binder );
        return null;
    }

    @Override
    public Void visit( final InjectionRequest<?> request )
    {
        analyzer.visit( request );
        request.applyTo( binder );
        return null;
    }

    @Override
    public Void visitOther( final Element element )
    {
        element.applyTo( binder );
        return null;
    }

    // ----------------------------------------------------------------------
    // Implementation methods
    // ----------------------------------------------------------------------

    private void mergeParameters( final Binding<?> binding )
    {
        Object parameters = null;
        if ( binding instanceof InstanceBinding<?> )
        {
            parameters = ( (InstanceBinding<?>) binding ).getInstance();
        }
        else if ( binding instanceof ProviderInstanceBinding<?> )
        {
            parameters = ( (ProviderInstanceBinding<?>) binding ).getProviderInstance().get();
        }
        if ( parameters instanceof Map )
        {
            properties.add( (Map<?, ?>) parameters );
        }
        else if ( parameters instanceof String[] )
        {
            Collections.addAll( arguments, (String[]) parameters );
        }
        else
        {
            Logs.warn( "Ignoring incompatible @Parameters binding: {}", binding, null );
        }
    }

    @SuppressWarnings( { "rawtypes", "unchecked" } )
    private void wireParameters( final Key key, final Map mergedProperties )
    {
        final TypeLiteral<?> type = key.getTypeLiteral();
        final Class<?> clazz = type.getRawType();
        if ( Map.class == clazz )
        {
            final TypeLiteral<?>[] constraints = TypeParameters.get( type );
            if ( constraints.length == 2 && String.class == constraints[1].getRawType() )
            {
                binder.bind( key ).toInstance( new StringProperties( mergedProperties ) );
            }
            else
            {
                binder.bind( key ).toInstance( mergedProperties );
            }
        }
        else if ( String[].class == clazz )
        {
            binder.bind( key ).toInstance( arguments.toArray( new String[arguments.size()] ) );
        }
    }

    @SuppressWarnings( "deprecation" )
    private static boolean isParameters( final Class<? extends Annotation> qualifierType )
    {
        return Parameters.class == qualifierType || org.sonatype.inject.Parameters.class == qualifierType;
    }
}
