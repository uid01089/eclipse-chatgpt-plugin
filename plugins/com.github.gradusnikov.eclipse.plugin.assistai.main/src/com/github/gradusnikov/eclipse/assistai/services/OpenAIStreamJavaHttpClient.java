package com.github.gradusnikov.eclipse.assistai.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gradusnikov.eclipse.assistai.Activator;
import com.github.gradusnikov.eclipse.assistai.model.ChatMessage;
import com.github.gradusnikov.eclipse.assistai.model.Conversation;
import com.github.gradusnikov.eclipse.assistai.preferences.PreferenceConstants;
import com.github.gradusnikov.eclipse.assistai.prompt.PromptLoader;

/**
 * A Java HTTP client for streaming requests to OpenAI API.
 * This class allows subscribing to responses received from the OpenAI API and processes the chat completions.
 */
@Creatable
public class OpenAIStreamJavaHttpClient
{

    private String API_KEY;
    private String API_URL = "https://api.openai.com/v1/chat/completions";
    private String MODEL;// = "gpt-4";

    private SubmissionPublisher<String> publisher;
    private Supplier<Boolean> isCancelled = () -> false;
    
    
    @Inject
    private ILog logger;
    
    @Inject
    private PromptLoader promptLoader;
    
    public OpenAIStreamJavaHttpClient()
    {
        publisher = new SubmissionPublisher<>();

    }
    
    public void setCancelPrivider( Supplier<Boolean> isCancelled )
    {
        this.isCancelled = isCancelled;
    }
    
    /**
     * Subscribes a given Flow.Subscriber to receive String data from OpenAI API responses.
     * @param subscriber the Flow.Subscriber to be subscribed to the publisher
     */
    public synchronized void subscribe(Flow.Subscriber<String> subscriber)
    {
        publisher.subscribe(subscriber);
    }
    /**
     * Returns the JSON request body as a String for the given prompt.
     * @param prompt the user input to be included in the request body
     * @return the JSON request body as a String
     */
    private String getRequestBody(Conversation prompt)
    {

        API_KEY = Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.OPENAI_API_KEY);
        MODEL = Activator.getDefault().getPreferenceStore().getString(PreferenceConstants.OPENAI_MODEL_NAME);


        Map<String, Object>       requestBody = new LinkedHashMap<>();
        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> systemMessage = new LinkedHashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", promptLoader.createPromptText("system-prompt.txt") );
        messages.add(systemMessage);

        for ( ChatMessage message : prompt.messages() )
        {
            Map<String, Object> userMessage = new LinkedHashMap<>();
            userMessage.put("role", message.getRole());
            userMessage.put("content", message.getContent() );
            messages.add(userMessage);
        }
        requestBody.put("model", MODEL);
        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("stream", true);

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonString;
        try
        {
            jsonString = objectMapper.writeValueAsString(requestBody);
        }
        catch (JsonProcessingException e)
        {
            throw new RuntimeException( e );
        }

        return jsonString;
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
    		HttpClient client = HttpClient.newHttpClient();
    		String requestBody = getRequestBody(prompt);
    		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(API_URL))
    				.header("Authorization", "Bearer " + API_KEY)
    				.header("Accept", "text/event-stream")
    				.header("Content-Type", "application/json")
    				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
    				.build();
    		
    		logger.info("Sending request to ChatGPT.");
    		
    		try
    		{
    			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    			
    			if (response.statusCode() != 200)
    			{
    				logger.error("Request failed: " + response);
    				throw new IOException("Request failed: " + response);
    			}
    			
    			try( BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8)) )
    			{
    				String line;
    				while ((line = reader.readLine()) != null && !isCancelled.get() )
    				{
    					if (line.startsWith("data:"))
    					{
    						String data = line.substring(5).trim();
    						if ("[DONE]".equals(data))
    						{
    							break;
    						} 
    						else
    						{
    							ObjectMapper mapper = new ObjectMapper();
    							JsonNode node = mapper.readTree(data).get("choices").get(0).get("delta");
    							if (node.has("content"))
    							{
    								String content = node.get("content").asText();
    								publisher.submit(content);
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
    			publisher.closeExceptionally(e);
    			throw new RuntimeException( e );
    		} 
    		finally
    		{
    			publisher.close();
    		}
    	};
    	

    }

}