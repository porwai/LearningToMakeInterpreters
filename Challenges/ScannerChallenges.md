CHALLENGES
1. The lexical grammars of Python and Haskell are not regular. What does that mean, and why aren’t they?

This is because each element in python can represent multiple things such as integers, doubles and strings - there is no regular consistent grammar

2. Aside from separating tokens—distinguishing print foo from printfoo—spaces aren’t used for much in most languages. However, in a couple of dark corners, a space does affect how code is parsed in CoffeeScript, Ruby, and the C preprocessor. Where and what effect does it have in each of those languages?


3. Our scanner here, like most, discards comments and whitespace since those aren’t needed by the parser. Why might you want to write a scanner that does not discard those? What would it be useful for?
It may be useful for readability if you were wanting to translate your code into machine language


4. Add support to Lox’s scanner for C-style /* ... */ block comments. Make sure to handle newlines in them. Consider allowing them to nest. Is adding support for nesting more work than you expected? Why?

I created a new fuction 
    
    private void blockComments() {
        int depth = 0;
        while (true) {
            // returns error if the comment is unclosed
            if (isAtEnd()) {
                Lox.error(line, "Unclosed Block Comment.");
                return;
            }
            char c = advance();
            if (c == '\n') line++;
            else if (c == '*' && peek() == '/') {
                advance(); // consumes '/';
                if (depth == 0) return;
                depth--;
            } else if (c == '/' && peek() == '*') {
                advance(); // consumes '*';
                depth++;
            }

        }
    }

 
and defined the case of / differently
  
    case '/':
                if (match('/')) {
                    // A comment goes until the end of the line.
                    // This does not add anything to token
                    while (peek() != '\n' && !isAtEnd()) advance();
                } else if (match('*')) {
                    blockComments();
                } else {
                    addToken(TokenType.SLASH);
                }
                break;
