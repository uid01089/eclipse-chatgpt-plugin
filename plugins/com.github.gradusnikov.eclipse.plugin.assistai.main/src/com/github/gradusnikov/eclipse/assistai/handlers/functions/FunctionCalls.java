package com.github.gradusnikov.eclipse.assistai.handlers.functions;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.e4.core.di.annotations.Creatable;

import com.github.gradusnikov.eclipse.assistai.commands.ReadJavaDocCommand;
import com.github.gradusnikov.eclipse.assistai.services.Function;
import com.github.gradusnikov.eclipse.assistai.services.FunctionParam;

@Creatable
@Singleton
public class FunctionCalls
{
    @Inject
    private ReadJavaDocCommand readJavaDocCommand;
    
    @Function(name="getCurrentWeather", description="Get the current weather in a given location", type="object")
    public WeatherReport getCurrentWeather( 
            @FunctionParam(name="location", description="The city and state, e.g. San Francisco, CA", required=true) String location, 
            @FunctionParam(name="unit", description="The temperature unit, e.g. celsius or fahrenheit" ) String unit )
    {
        return new WeatherReport ( location, 72, unit, new String[]{"sunny", "windy"});
    }
    
    public record WeatherReport( String location, int degrees, String unit, String[] forecast ) {};
    
    
    @Function(name="getJavaDoc", description="Get the JavaDoc for the given class.", type="object")
    public String getJavaDoc(
            @FunctionParam(name="fullyQualifiedClassName", description="A fully qualified class name of the Java class", required=true) String fullyQualifiedClassName)
    {
        return readJavaDocCommand.getClassAttachedJavadoc( fullyQualifiedClassName );
    }
    @Function(name="getSource", description="Get the source for the given class.", type="object")
    public String getSource(
            @FunctionParam(name="fullyQualifiedClassName", description="A fully qualified class name of the Java class", required=true) String fullyQualifiedClassName)
    {
        return readJavaDocCommand.getClassAttachedSource( fullyQualifiedClassName );
    }

}
