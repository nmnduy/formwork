# FormWork

A java library to construct java objects from LLM output

## Usage

### Model Definition and Basic Example

```java
// Define your data class
public record UserProfile(String name, int age, String email) {}

// Create FormWork and LLM caller
import io.filesurf.formwork.FormWork;
import java.util.function.Function;

FormWork formWork = new FormWork();
Function<String, String> llmCaller = prompt -> {
    // call your LLM API and return JSON string
    return "{ \"name\": \"Alice\", \"age\": 30, \"email\": \"alice@example.com\" }";
};

// Construct a UserProfile
UserProfile result = formWork.construct(
    UserProfile.class,
    "Generate a UserProfile object",
    llmCaller
);
```

### Custom Retries

```java
// Retry with custom attempts for UserProfile
UserProfile result = formWork.construct(
    UserProfile.class,
    "Generate a UserProfile object",
    llmCaller,
    5
);
```
