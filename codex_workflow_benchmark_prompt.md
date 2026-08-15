" Let's make comprehensive changes and upgrades to the application:

Similar interface to chatGPT: Merge the two OCR windows and the chat window into a single full-screen chat window, with a default dark interface. Sessions will be moved to the far left side of the chat window, and the quick chat buttons will be moved to the right. Move the two current quick chat buttons to the top right corner. They will be stacked instead of horizontal.

Add two tool buttons, "run OCR" and "search web," in the bottom right corner, also stacked. The "run OCR" button is green, and the "search web" button is blue. Both are slightly transparent.

"run OCR" will activate the OCR service, extract the image pasted into the chat, and return it in the chatbot's response.

For "search web," use the Brave Search API with LLM context functionality. The setup and API key are already available in the project ../F1_fact_checker; please reuse it. When this button is pressed, the "search web" icon will be inserted into the user's question input field.

Ensures that the text layout in the input image is preserved in the output.

Allows pasting and OCR of multiple images in one session, similar to web GPT chat.

Currently, the gemma e2b model in the LLM service has its view disabled; please re-enable it so the chatbot can see the images sent by the user and respond.

Monitor the application interface at https://jetsonocrai.cc/ to ensure the interface is accurate and visually appealing. "
