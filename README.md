# zio-auth

## Description
This project demonstrates how to implement authentication using `zio-http`. It includes features for managing user creation and password setting workflows. While the project is designed to be reusable as a library for other projects, it may not be generic enough for widespread use.

### Key Features:
- **Authentication**: Provides routes for login, logout, and token management.
- **User Management**: Includes workflows for user creation and password setting.
- **Frontend Integration**: Uses `scalajs-react` for the frontend, with a sample `LoginRouter` provided for easy integration.

## Usage

### As a Library
To use this project as a library:
1. Override the `AuthServer` class and provide it in your environment.
2. This will give you access to the authentication routes, which you can add to your `zio-http` server.

### Frontend Integration
On the frontend, the project uses `scalajs-react`. You can include the `LoginRouter` component into your application to handle authentication workflows. A sample implementation is provided in the project to help you get started.

## Documentation
For more details, refer to the [zio-auth Microsite](https://zio.github.io/zio-auth/).

## Contributing
Contributions are welcome! Please refer to the [Contributing Guide](https://zio.github.io/zio-auth/docs/about/about_contributing) for more information.

## Code of Conduct
This project adheres to the [Code of Conduct](https://zio.github.io/zio-auth/docs/about/about_coc). Please read it to understand the expectations for participation.

## Support
If you need help, feel free to join the discussion on [Discord](https://discord.gg/2ccFBr4).

## License
This project is licensed under the terms of the [License](LICENSE).
