import { render, screen } from '../../test-utils';
import { ProblemAlert } from './ProblemAlert';

describe('ProblemAlert', () => {
    it('says what did not work, in an alert', () => {
        render(<ProblemAlert message="That code is taken, or reserved. Choose another." />);

        expect(screen.getByRole('alert')).toHaveTextContent(
            'That code is taken, or reserved. Choose another.'
        );
    });
});
