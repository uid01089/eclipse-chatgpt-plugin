package com.github.gradusnikov.eclipse.assistai.services;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceStore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.commands.FunctionExecutorProvider;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.model.Incoming;
import com.github.gradusnikov.eclipse.assistai.model.ModelApiDescriptor;
import com.github.gradusnikov.eclipse.assistai.part.Attachment;
import com.github.gradusnikov.eclipse.assistai.prompt.PromptLoader;
import com.github.gradusnikov.eclipse.assistai.prompt.Prompts;
import com.github.gradusnikov.eclipse.assistai.tools.ImageUtilities;

import jakarta.inject.Inject;

/**
 * A Java HTTP client for streaming requests to OpenAI API.
 * This class allows subscribing to responses received from the OpenAI API and processes the chat completions.
 */
@Creatable
public class OpenAIStreamJavaHttpClient
{
    private SubmissionPublisher<Incoming> publisher;
    
    private Supplier<Boolean> isCancelled = () -> false;
    
    
    
    @Inject
    private ILog logger;
    
    @Inject
    private OpenAIClientConfiguration configuration;
    
    @Inject
    private FunctionExecutorProvider functionExecutor;
    
    private IPreferenceStore preferenceStore;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    
    public OpenAIStreamJavaHttpClient()
    {
       
        publisher = new SubmissionPublisher<>();
        preferenceStore = Activator.getDefault().getPreferenceStore();
    }
    
    public void setCancelProvider( Supplier<Boolean> isCancelled )
    {
        this.isCancelled = isCancelled;
    }
    
    /**
     * Subscribes a given Flow.Subscriber to receive String data from OpenAI API responses.
     * @param subscriber the Flow.Subscriber to be subscribed to the publisher
     */
    public synchronized void subscribe(Flow.Subscriber<Incoming> subscriber)
    {
        publisher.subscribe(subscriber);
    }
    /**
     * Returns the JSON request body as a String for the given prompt.
     * @param prompt the user input to be included in the request body
     * @return the JSON request body as a String
     */
    private String getRequestBody(Conversation prompt, ModelApiDescriptor model)
    {
        try
        {
            
            
            var requestBody = new LinkedHashMap<String, Object>();
            var messages = new ArrayList<Map<String, Object>>();
    
            var systemMessage = new LinkedHashMap<String, Object> ();
            systemMessage.put("role", "system");
            systemMessage.put("content",  preferenceStore.getString( Prompts.SYSTEM.preferenceName() ));
            messages.add(systemMessage);
            
            
            prompt.messages().stream().map( message -> toJsonPayload(message, model) ).forEach( messages::add );
            
            requestBody.put("model", model.modelName() );
            if ( model.functionCalling() )
            {
                requestBody.put("functions", AnnotationToJsonConverter.convertDeclaredFunctionsToJson( functionExecutor.get().getFunctions() ) );
            }
            requestBody.put("messages", messages);
            requestBody.put("temperature", model.temperature()/10);
            requestBody.put("stream", true);
    
            String jsonString;
            jsonString = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestBody);
            return jsonString;
        }
        catch (JsonProcessingException e)
        {
            throw new RuntimeException( e );
        }
    }

    private LinkedHashMap<String, Object> toJsonPayload( ChatMessage message, ModelApiDescriptor model )
    {
        try
        {
            var userMessage = new LinkedHashMap<String,Object>();
            userMessage.put("role", message.getRole());
            if ( model.functionCalling() )
            {
                // function call results
                if ( Objects.nonNull( message.getName() ) )
                {
                    userMessage.put( "name", message.getName() );
                }
                if ( Objects.nonNull( message.getFunctionCall() ) )
                {
                    var functionCallObject = new LinkedHashMap<String, String> ();
                    functionCallObject.put( "name", message.getFunctionCall().name() );
                    functionCallObject.put( "arguments", objectMapper.writeValueAsString(  message.getFunctionCall().arguments() ) );
                    userMessage.put( "function_call", functionCallObject );
                }
            }
            
            // assemble text content
            List<String> textParts = message.getAttachments()
                    .stream()
                    .map( Attachment::toChatMessageContent )
                    .filter( Objects::nonNull )
                    .collect( Collectors.toList() );
            String textContent = String.join( "\n", textParts ) + "\n\n" + message.getContent();
           
            // add image content
            if ( model.vision() )
            {
                var content = new ArrayList<>();
                var textObject = new LinkedHashMap<String, String> ();
                textObject.put( "type", "text" );
                textObject.put( "text", textContent );
                content.add( textObject );
                message.getAttachments()
                       .stream()
                       .map( Attachment::getImageData )
                       .filter( Objects::nonNull )
                       .map( ImageUtilities::toBase64Jpeg )
                       .map( this::toImageUrl )
                       .forEachOrdered( content::add );
                userMessage.put( "content", content );
            }
            else // legacy API - just put content as text
            {
                userMessage.put( "content", textContent );
            }
            return userMessage;
        }
        catch ( Exception e )
        {
            throw new RuntimeException( e );
        }
    }

    

    /**
     * Converts a base64-encoded image data string into a structured JSON object suitable for API transmission.
     * <p>
     * This method constructs a JSON object that encapsulates the image data in a format expected by the API.
     * The 'image_url' key is an object containing a 'url' key, which holds the base64-encoded image data prefixed
     * with the appropriate data URI scheme.
     *
     * @param data the base64-encoded string of the image data
     * @return a LinkedHashMap where the key 'type' is set to 'image_url', and 'image_url' is another LinkedHashMap
     *         containing the 'url' key with the full data URI of the image.
     */
    private LinkedHashMap<String, Object> toImageUrl(String data)
    {
        var imageObject = new LinkedHashMap<String, Object>();
        imageObject.put("type", "image_url");
        var urlObject = new LinkedHashMap<String, String>();
        urlObject.put("url", "data:image/jpeg;base64," + data);
        imageObject.put("image_url", urlObject);
        return imageObject;
    }
 
    /**
     * Creates and returns a Runnable that will execute the HTTP request to OpenAI API
     * with the given conversation prompt and process the responses.
     * <p>
     * Note: this method does not block and the returned Runnable should be executed
     * to perform the actual HTTP request and processing.
     *
     * @param prompt the conversation to be sent to the OpenAI API
     * @return a Runnable that performs the HTTP request and processes the responses
     */
    public Runnable run( Conversation prompt ) 
    {
    	return () ->  {
    		
            var model = configuration.getSelectedModel().orElseThrow();
    	    
    	    HttpClient client = HttpClient.newBuilder()
    		                              .connectTimeout( Duration.ofSeconds(configuration.getConnectionTimoutSeconds()) )
    		                              .build();
    		
    		String requestBody = getRequestBody(prompt, model);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(model.apiUrl()))
                    .timeout( Duration.ofSeconds( configuration.getRequestTimoutSeconds() ) )
                    .version(HttpClient.Version.HTTP_1_1)
    				.header("Authorization", "Bearer " + model.apiKey())
    				.header("Accept", "text/event-stream")
    				.header("Content-Type", "application/json")
    				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
    				.build();
    		
    		logger.info("Sending request to ChatGPT.\n\n" + requestBody);
    		
    		try
    		{
    			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    			
    			if (response.statusCode() != 200)
    			{
    			    logger.error("Request failed with status code: " + response.statusCode() + " and response body: " + new String(response.body().readAllBytes()));
    			}
    			try (var inputStream = response.body();
    			     var inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
    			     var reader = new BufferedReader(inputStreamReader)) 
    			{
    				String line;
    				while ((line = reader.readLine()) != null && !isCancelled.get() )
    				{
    					if (line.startsWith("data:"))
    					{
    					    var data = line.substring(5).trim();
    						if ("[DONE]".equals(data))
    						{
    							break;
    						} 
    						else
    						{
    						    var mapper = new ObjectMapper();
    						    var choice = mapper.readTree(data).get("choices").get(0);
    						    var node =  choice.get("delta");
    							if (node.has("content") )
    							{
    							    var content = node.get("content").asText();
    							    if ( !"null".equals( content ) )
    							    {
    							        publisher.submit(new Incoming(Incoming.Type.CONTENT, content));
    							    }
    							}
    							if ( node.has( "function_call" ) )
    							{
    							    var functionNode = node.get( "function_call" );
    							    if ( functionNode.has( "name" ) )
    							    {
    							        publisher.submit( new Incoming(Incoming.Type.FUNCTION_CALL, String.format( "\"function_call\" : { \n \"name\": \"%s\",\n \"arguments\" :", functionNode.get("name").asText() ) ) );
    							    }
    							    if ( functionNode.has( "arguments" ) )
    							    {
    							        publisher.submit( new Incoming(Incoming.Type.FUNCTION_CALL, node.get("function_call").get("arguments").asText()) );
    							    }
    							}
    						}
    					}
    				}
    			}
    			if ( isCancelled.get() )
    			{
    				publisher.closeExceptionally( new CancellationException() );
    			}
    		}
    		catch (Exception e)
    		{
    		    logger.error( e.getMessage(), e );
    			publisher.closeExceptionally(e);
    		} 
    		finally
    		{
    			publisher.close();
    		}
    	};
    }

}